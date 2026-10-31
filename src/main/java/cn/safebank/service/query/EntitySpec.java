package cn.safebank.service.query;

import java.util.*;

/**
 * 一个可查询实体的声明：字段、可过滤/可分组/可排序、度量、时间轴，以及**不可查字段**。
 *
 * <p>新增查询能力只在这里加一段声明，不需要新增工具、也不需要新写提示词话术。
 * ``unsupported`` 是这套设计里"边界"的唯一来源：没列在这里、又没数据的情况不叫边界，叫缺陷。</p>
 */
public final class EntitySpec {
    public enum Kind { TEXT, MONEY, DATE, NUMBER, BOOL }

    public record Field(String name, Kind kind, String label, boolean filterable, boolean groupable, boolean sortable) {}

    private final String name;
    private final String label;
    private final String timeField;
    private final String measureField;
    private final Map<String, Field> fields;
    private final Set<String> metrics;
    private final Map<String, String> unsupported;
    private final List<String> notes;

    private EntitySpec(Builder b) {
        name = b.name; label = b.label; timeField = b.timeField; measureField = b.measureField;
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
        metrics = Collections.unmodifiableSet(new LinkedHashSet<>(b.metrics));
        unsupported = Collections.unmodifiableMap(new LinkedHashMap<>(b.unsupported));
        notes = List.copyOf(b.notes);
    }

    public static Builder of(String name, String label) { return new Builder(name, label); }

    public String name() { return name; }
    public String label() { return label; }
    public String timeField() { return timeField; }
    public String measureField() { return measureField; }
    public Map<String, Field> fields() { return fields; }
    public Field field(String field) { return fields.get(field); }
    public Set<String> metrics() { return metrics; }
    public Map<String, String> unsupported() { return unsupported; }
    public List<String> notes() { return notes; }
    public List<String> fieldNames() { return List.copyOf(fields.keySet()); }
    public List<String> filterableFields() {
        return fields.values().stream().filter(Field::filterable).map(Field::name).toList();
    }
    public List<String> groupableFields() {
        return fields.values().stream().filter(Field::groupable).map(Field::name).toList();
    }
    public List<String> sortableFields() {
        return fields.values().stream().filter(Field::sortable).map(Field::name).toList();
    }

    public static final class Builder {
        private final String name;
        private final String label;
        private String timeField;
        private String measureField;
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private final Set<String> metrics = new LinkedHashSet<>(Set.of("count"));
        private final Map<String, String> unsupported = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();

        private Builder(String name, String label) { this.name = name; this.label = label; }

        /** 时间轴字段：带时间的实体才会按 range 过滤。 */
        public Builder time(String field) { timeField = field; return this; }

        /** 默认度量字段：sum/avg/max/min 作用在它上面。 */
        public Builder measure(String field) { measureField = field; return this; }

        public Builder field(String name, Kind kind, String label) {
            return field(name, kind, label, true, false, true);
        }

        public Builder field(String name, Kind kind, String label, boolean filterable, boolean groupable, boolean sortable) {
            fields.put(name, new Field(name, kind, label, filterable, groupable, sortable));
            return this;
        }

        public Builder metrics(String... names) { metrics.addAll(List.of(names)); return this; }

        /** 记录"这个实体查不到什么、为什么"，回答里的边界说明直接引用它。 */
        public Builder unsupported(String field, String reason) { unsupported.put(field, reason); return this; }

        public Builder note(String text) { notes.add(text); return this; }

        public EntitySpec build() { return new EntitySpec(this); }
    }
}
