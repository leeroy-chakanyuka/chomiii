package com.chomiii.approval;

import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolSchemaValidator;
import com.chomiii.planning.Plan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-side lifecycle only. Callers must authenticate the owner and obtain explicit user consent. */
@Service
public class ApprovalService {
    public static final Duration MAX_PENDING_LIFETIME = Duration.ofMinutes(15);
    private static final int MAX_DEPTH = 8;
    private static final int MAX_COLLECTION = 100;
    private static final int MAX_ARGUMENT_BYTES = 4000;
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED, CONSUMED }

    public record Approval(UUID id, String owner, UUID conversationId, UUID requestId, String toolName,
            String capability, ToolDefinition.Effect effect, String argumentsJson, String argumentsDigest,
            Instant expiresAt, Status status, UUID planId, UUID stepId, UUID resultRef, String definitionDigest, String preview) {}

    /** Must use the current Spring transaction and only transactional database effects. */
    @FunctionalInterface
    public interface Executor { UUID execute(Approval action); }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ThreadLocal<UUID> executing = new ThreadLocal<>();

    public ApprovalService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Approval create(String owner, UUID conversationId, UUID requestId, ToolDefinition tool,
            Map<String, Object> arguments, Instant expiresAt, UUID planId, UUID stepId) {
        requireOwner(owner, conversationId, true);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(tool);
        if ((planId == null) != (stepId == null)) throw new IllegalArgumentException("Plan and step must be supplied together");
        String canonical = canonical(tool, arguments);
        if (planId != null) requirePlanStep(owner, conversationId, planId, stepId, tool.name());
        String argumentDigest = digest(canonical);
        String definitionDigest = definitionDigest(tool);
        String identity = proposalIdentity(owner, conversationId, requestId, tool, argumentDigest, planId, stepId);
        var existing = jdbc.query("select a.* from action_approvals a where a.proposal_identity = ? and a.owner_hash = ? and a.conversation_id = ? for update",
                (row, index) -> map(row), identity, owner, conversationId.toString());
        if (!existing.isEmpty()) {
            Approval previous = existing.get(0);
            if (!previous.requestId().equals(requestId) || !previous.toolName().equals(tool.name())
                    || !previous.capability().equals(tool.capability()) || previous.effect() != tool.effect()
                    || !previous.argumentsDigest().equals(argumentDigest) || !previous.argumentsJson().equals(canonical)
                    || !Objects.equals(previous.planId(), planId) || !Objects.equals(previous.stepId(), stepId))
                throw new IllegalStateException("Proposal identity collision");
            if (!previous.definitionDigest().equals(definitionDigest)) throw new IllegalStateException("Tool definition changed");
            return previous;
        }
        Instant now = Instant.now();
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_PENDING_LIFETIME)))
            throw new IllegalArgumentException("Invalid expiry");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into action_approvals (id, owner_hash, conversation_id, request_id, proposal_identity, tool_name, capability, effect, arguments_json, arguments_digest, definition_digest, expires_at, status, plan_id, step_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id.toString(), owner, conversationId.toString(), requestId.toString(), identity, tool.name(), tool.capability(),
                tool.effect().name(), canonical, argumentDigest, definitionDigest, Timestamp.from(expiresAt), Status.PENDING.name(),
                string(planId), string(stepId));
        return findLocked(owner, conversationId, id);
    }

    @Transactional(readOnly = true)
    public List<Approval> list(String owner, UUID conversationId) {
        requireOwner(owner, conversationId, false);
        return jdbc.query("select a.* from action_approvals a join conversations c on c.id = a.conversation_id and c.owner_hash = a.owner_hash where a.owner_hash = ? and a.conversation_id = ? order by a.id",
                (row, index) -> map(row), owner, conversationId.toString());
    }

    @Transactional
    public Approval reject(String owner, UUID conversationId, UUID id) {
        return close(owner, conversationId, id, Status.REJECTED);
    }

    @Transactional
    public Approval cancel(String owner, UUID conversationId, UUID id) {
        return close(owner, conversationId, id, Status.CANCELLED);
    }

    /** The definition and arguments are the exact proposal the user is confirming, not model authority. */
    @Transactional
    public UUID approveAndExecute(String owner, UUID conversationId, UUID id, ToolDefinition tool,
            Map<String, Object> arguments, Executor executor) {
        Approval action = findLocked(owner, conversationId, id);
        String canonical = canonical(Objects.requireNonNull(tool), arguments);
        if (!action.toolName().equals(tool.name()) || !action.capability().equals(tool.capability())
                || action.effect() != tool.effect() || !action.argumentsJson().equals(canonical)
                || !action.argumentsDigest().equals(digest(canonical)) || !action.definitionDigest().equals(definitionDigest(tool))) throw new IllegalArgumentException("Action does not match approval");
        if (action.status() == Status.CONSUMED) return action.resultRef();
        if (action.status() != Status.PENDING || !action.expiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("Approval is not pending and unexpired");
        if (executing.get() != null) throw new IllegalStateException("Nested approval execution is not allowed");
        if (jdbc.update("update action_approvals set status = ? where id = ? and owner_hash = ? and conversation_id = ? and status = ?",
                Status.APPROVED.name(), id.toString(), owner, conversationId.toString(), Status.PENDING.name()) != 1)
            throw new IllegalStateException("Approval could not be authorized");
        UUID result;
        executing.set(id);
        try { result = Objects.requireNonNull(executor, "executor").execute(action); }
        finally { executing.remove(); }
        Objects.requireNonNull(result, "result reference");
        int changed = jdbc.update("update action_approvals set status = ?, result_ref = ? where id = ? and owner_hash = ? and conversation_id = ? and status = ?",
                Status.CONSUMED.name(), result.toString(), id.toString(), owner, conversationId.toString(), Status.APPROVED.name());
        if (changed != 1) throw new IllegalStateException("Approval could not be consumed");
        return result;
    }

    /** Authorization is valid only on the thread synchronously executing this approval's transaction. */
    public boolean isAuthorized(String owner, UUID conversationId, UUID requestId, UUID approvalId,
            ToolDefinition tool, Map<String, Object> arguments) {
        if (approvalId == null || !approvalId.equals(executing.get())
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return false;
        try {
            String canonical = canonical(tool, arguments);
            return jdbc.query("select a.* from action_approvals a where a.id = ? and a.owner_hash = ? and a.conversation_id = ? and a.request_id = ? and a.status = ?",
                    (row, index) -> map(row), approvalId.toString(), owner, conversationId.toString(), requestId.toString(), Status.APPROVED.name())
                    .stream().anyMatch(a -> a.expiresAt().isAfter(Instant.now()) && a.toolName().equals(tool.name())
                            && a.capability().equals(tool.capability()) && a.effect() == tool.effect()
                            && a.definitionDigest().equals(definitionDigest(tool)) && a.argumentsJson().equals(canonical)
                            && a.argumentsDigest().equals(digest(canonical)));
        } catch (RuntimeException invalid) { return false; }
    }

    @Transactional
    public Approval lock(String owner, UUID conversationId, UUID id) { return findLocked(owner, conversationId, id); }

    @Transactional
    public void lockConversation(String owner, UUID conversationId) { requireOwner(owner, conversationId, true); }

    @Transactional
    public void lockPlanForConversation(String owner, UUID conversationId, UUID planId) {
        requireOwner(owner, conversationId, true);
        lockPlan(owner, conversationId, planId);
    }

    @Transactional
    public Approval setPreview(String owner, UUID conversationId, UUID id, String preview) {
        Approval action = findLocked(owner, conversationId, id);
        if (action.preview() == null) {
            jdbc.update("update action_approvals set preview = ? where id = ? and owner_hash = ? and conversation_id = ? and preview is null",
                    Objects.requireNonNull(preview), id.toString(), owner, conversationId.toString());
            return findLocked(owner, conversationId, id);
        }
        if (!action.preview().equals(preview)) throw new IllegalStateException("Proposal preview changed");
        return action;
    }

    private Approval close(String owner, UUID conversationId, UUID id, Status status) {
        Approval action = findLocked(owner, conversationId, id);
        if (action.status() != Status.PENDING || !action.expiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("Approval is not pending and unexpired");
        if (jdbc.update("update action_approvals set status = ? where id = ? and owner_hash = ? and conversation_id = ? and status = ?",
                status.name(), id.toString(), owner, conversationId.toString(), Status.PENDING.name()) != 1)
            throw new IllegalStateException("Approval could not be closed");
        return findLocked(owner, conversationId, id);
    }

    private void requireOwner(String owner, UUID conversationId, boolean lock) {
        if (owner == null || owner.isBlank() || conversationId == null) throw new IllegalArgumentException("Owner and conversation required");
        if (lock && jdbc.query("select owner_hash from browser_sessions where owner_hash = ? for update",
                (row, index) -> row.getString(1), owner).isEmpty()) throw new IllegalArgumentException("Approval not found");
        var rows = jdbc.query("select id from conversations where id = ? and owner_hash = ?" + (lock ? " for update" : ""),
                (row, index) -> row.getString(1), conversationId.toString(), owner);
        if (rows.isEmpty()) throw new IllegalArgumentException("Approval not found");
    }

    private Approval findLocked(String owner, UUID conversationId, UUID id) {
        requireOwner(owner, conversationId, true);
        Objects.requireNonNull(id);
        var plans = jdbc.query("select plan_id from action_approvals where id = ? and owner_hash = ? and conversation_id = ?",
                (row, index) -> java.util.Optional.ofNullable(row.getString(1)), id.toString(), owner, conversationId.toString());
        if (plans.isEmpty()) throw new IllegalArgumentException("Approval not found");
        if (plans.get(0).isPresent()) lockPlan(owner, conversationId, UUID.fromString(plans.get(0).get()));
        return jdbc.query("select a.* from action_approvals a where a.id = ? and a.owner_hash = ? and a.conversation_id = ? for update",
                (row, index) -> map(row), id.toString(), owner, conversationId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Approval not found"));
    }

    private String lockPlan(String owner, UUID conversationId, UUID planId) {
        return jdbc.query("select proposal_json from plan_executions where id = ? and owner_hash = ? and conversation_id = ? for update",
                (row, index) -> row.getString(1), planId.toString(), owner, conversationId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Plan step not found"));
    }

    private void requirePlanStep(String owner, UUID conversationId, UUID planId, UUID stepId, String capability) {
        String proposal = lockPlan(owner, conversationId, planId);
        var steps = jdbc.query("select step_id from plan_execution_steps where plan_id = ? and execution_id = ? for update",
                (row, index) -> row.getString(1), planId.toString(), stepId.toString());
        if (steps.isEmpty()) throw new IllegalArgumentException("Plan step not found");
        try {
            Plan plan = json.readValue(proposal, Plan.class);
            if (!plan.id().equals(planId) || plan.steps().stream().noneMatch(step -> step.id().equals(steps.get(0))
                    && step.capability().equals(capability))) throw new IllegalArgumentException("Plan step not found");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid stored plan");
        }
    }

    private String proposalIdentity(String owner, UUID conversationId, UUID requestId, ToolDefinition tool,
            String argumentDigest, UUID planId, UUID stepId) {
        try {
            return digest(json.writeValueAsString(List.of(owner, conversationId.toString(), requestId.toString(),
                    tool.name(), tool.capability(), tool.effect().name(), argumentDigest,
                    planId == null ? "" : planId.toString(), stepId == null ? "" : stepId.toString())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot identify proposal");
        }
    }

    private String canonical(ToolDefinition tool, Map<String, Object> arguments) {
        Object normalized = normalize(arguments, 0, new int[] {0});
        ToolSchemaValidator.validateSchema(tool.parameters());
        @SuppressWarnings("unchecked")
        Map<String, Object> safeArguments = (Map<String, Object>) normalized;
        ToolSchemaValidator.validate(tool.parameters(), safeArguments);
        try {
            byte[] encoded = json.writeValueAsBytes(normalized);
            if (encoded.length > MAX_ARGUMENT_BYTES) throw new IllegalArgumentException("Arguments are too large");
            return new String(encoded, StandardCharsets.UTF_8);
        }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid arguments"); }
    }

    private static Object normalize(Object value, int depth, int[] budget) {
        if (depth > MAX_DEPTH || ++budget[0] > MAX_ARGUMENT_BYTES) throw new IllegalArgumentException("Arguments are too large or nested");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_COLLECTION) throw new IllegalArgumentException("Arguments are too large");
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid arguments");
                budget[0] += key.length();
                if (budget[0] > MAX_ARGUMENT_BYTES) throw new IllegalArgumentException("Arguments are too large");
                String name = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
                if (name.contains("apikey") || name.contains("credential") || name.contains("password") || name.contains("token") || name.contains("secret"))
                    throw new IllegalArgumentException("Credential fields are not allowed");
                sorted.put(key, normalize(entry.getValue(), depth + 1, budget));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_COLLECTION) throw new IllegalArgumentException("Arguments are too large");
            var result = new java.util.ArrayList<Object>(list.size());
            for (Object item : list) result.add(normalize(item, depth + 1, budget));
            return result;
        }
        if (value instanceof String text) {
            budget[0] += text.length();
            if (budget[0] > MAX_ARGUMENT_BYTES) throw new IllegalArgumentException("Arguments are too large");
            return text;
        }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof java.math.BigDecimal decimal
                && (decimal.precision() > MAX_ARGUMENT_BYTES || Math.abs((long) decimal.scale()) > MAX_ARGUMENT_BYTES))
            throw new IllegalArgumentException("Arguments are too large");
        if ((value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) && Double.isFinite(((Number) value).doubleValue())) return value;
        throw new IllegalArgumentException("Invalid arguments");
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String definitionDigest(ToolDefinition tool) {
        try { return digest(json.writeValueAsString(List.of(tool.name(), tool.description(), sortJson(tool.parameters()), tool.capability(), tool.effect().name()))); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid tool definition"); }
    }

    private static Object sortJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, sortJson(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ApprovalService::sortJson).toList();
        return value;
    }

    private static String string(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }

    private static Approval map(ResultSet row) throws SQLException {
        return new Approval(uuid(row.getString("id")), row.getString("owner_hash"), uuid(row.getString("conversation_id")),
                uuid(row.getString("request_id")), row.getString("tool_name"), row.getString("capability"),
                ToolDefinition.Effect.valueOf(row.getString("effect")), row.getString("arguments_json"),
                row.getString("arguments_digest"), row.getTimestamp("expires_at").toInstant(),
                Status.valueOf(row.getString("status")), uuid(row.getString("plan_id")), uuid(row.getString("step_id")),
                uuid(row.getString("result_ref")), row.getString("definition_digest"), row.getString("preview"));
    }
}
