package com.devassisthub.sql.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;

/**
 * Structural read-only check (FR-9) plus function and pattern policy (FR-10). Accepts only a single statement
 * whose every node is a SELECT variant and which calls no blocked function. Stateless and thread-safe; never
 * executes SQL.
 */
public class ReadOnlyValidator {

    static final String WARN_PG_SLEEP =
            "pg_sleep deliberately delays the query; remove it unless the delay is intended.";
    static final String WARN_CROSS_JOIN =
            "CROSS JOIN returns every row combination; add a join condition unless a Cartesian product is intended.";
    static final String WARN_COMMA_JOIN =
            "Comma join has no column equality linking the tables, so it returns every row combination; add a join condition.";
    static final String WARN_SELECT_STAR =
            "SELECT * without LIMIT or aggregation may return every row and column; list the columns or add a LIMIT.";

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "array_agg",
            "string_agg", "bool_and", "bool_or", "every", "json_agg", "jsonb_agg", "json_object_agg",
            "jsonb_object_agg", "percentile_cont", "percentile_disc", "mode", "stddev", "variance");

    private final FunctionPolicy policy;

    public ReadOnlyValidator() {
        this(FunctionPolicy.defaults());
    }

    public ReadOnlyValidator(FunctionPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    public ValidationResult validate(String sql) {
        String normalized = SqlNormalizer.normalize(sql);
        if (normalized.isEmpty()) {
            return rejected(normalized, RejectionCode.SQL_PARSE_ERROR, "No SQL statement found.");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(normalized);
        } catch (JSQLParserException | RuntimeException e) {
            return rejected(normalized, RejectionCode.SQL_PARSE_ERROR, "The SQL could not be parsed.");
        }
        List<Statement> list = statements == null ? List.of() : statements;
        if (list.isEmpty()) {
            return rejected(normalized, RejectionCode.SQL_PARSE_ERROR, "No SQL statement found.");
        }
        if (list.size() > 1) {
            return rejected(normalized, RejectionCode.MULTIPLE_STATEMENTS,
                    "Only a single statement is allowed; found " + list.size() + ".");
        }
        Statement statement = list.get(0);
        if (!(statement instanceof Select select)) {
            return rejected(normalized, RejectionCode.NON_SELECT_STATEMENT,
                    "Only SELECT statements are read-only; found " + kind(statement) + ".");
        }
        Set<Rejection> found = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        try {
            walk(select, found, warnings);
            if (topLevelSelectStar(select)) {
                warnings.add(WARN_SELECT_STAR);
            }
        } catch (RuntimeException e) {
            // Fail closed: a tree we cannot fully walk is not proven read-only.
            found.add(new Rejection(RejectionCode.SQL_PARSE_ERROR, "The SQL could not be fully analysed."));
        }
        // Warnings only accompany accepted statements.
        List<String> warningList = found.isEmpty() ? new ArrayList<>(warnings) : List.of();
        return new ValidationResult(found.isEmpty(), normalized, new ArrayList<>(found), warningList);
    }

    private static ValidationResult rejected(String normalized, RejectionCode code, String reason) {
        return new ValidationResult(false, normalized, List.of(new Rejection(code, reason)), List.of());
    }

    private static String kind(Statement statement) {
        String name = statement.getClass().getSimpleName();
        if (name.endsWith("Statement") && name.length() > "Statement".length()) {
            name = name.substring(0, name.length() - "Statement".length());
        }
        return name.toUpperCase(Locale.ROOT);
    }

    /**
     * Visits every select node (set-operation branches, CTE bodies, sub-selects in FROM/WHERE/SELECT/JOIN)
     * and every function call by reusing JSqlParser's deparser traversal, which walks the complete tree.
     */
    private void walk(Select select, Set<Rejection> found, Set<String> warnings) {
        StringBuilder sink = new StringBuilder();
        ExpressionDeParser expressions = new CheckingExpressionDeParser(policy, found, warnings);
        CheckingSelectDeParser selects = new CheckingSelectDeParser(expressions, sink, found, warnings);
        expressions.setSelectVisitor(selects);
        expressions.setBuilder(sink);
        select.accept((net.sf.jsqlparser.statement.select.SelectVisitor<StringBuilder>) selects, null);
    }

    /** Top-level plain SELECT with a bare {@code *}, no LIMIT/FETCH/TOP, no GROUP BY/HAVING and no aggregate. */
    private static boolean topLevelSelectStar(Select select) {
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null) {
            return false;
        }
        boolean star = plain.getSelectItems().stream().anyMatch(item -> item.getExpression() instanceof AllColumns);
        if (!star || plain.getLimit() != null || plain.getFetch() != null || plain.getTop() != null
                || plain.getGroupBy() != null || plain.getHaving() != null) {
            return false;
        }
        List<String> names = new ArrayList<>();
        ExpressionDeParser collector = new ExpressionDeParser() {
            @Override
            public <S> StringBuilder visit(Function function, S context) {
                names.add(FunctionPolicy.bareName(function.getMultipartName(), function.getName()));
                return super.visit(function, context);
            }
        };
        wire(collector);
        for (SelectItem<?> item : plain.getSelectItems()) {
            item.getExpression().accept(collector, null);
        }
        return names.stream().noneMatch(AGGREGATES::contains);
    }

    /** True when the expression contains a {@code column = column} predicate between different qualifiers. */
    private static boolean hasColumnEquality(Expression where) {
        if (where == null) {
            return false;
        }
        boolean[] seen = {false};
        ExpressionDeParser finder = new ExpressionDeParser() {
            @Override
            public <S> StringBuilder visit(EqualsTo equalsTo, S context) {
                if (equalsTo.getLeftExpression() instanceof Column left
                        && equalsTo.getRightExpression() instanceof Column right
                        && !qualifier(left).equalsIgnoreCase(qualifier(right))) {
                    seen[0] = true;
                }
                return super.visit(equalsTo, context);
            }
        };
        wire(finder);
        where.accept(finder, null);
        return seen[0];
    }

    private static void wire(ExpressionDeParser expressions) {
        StringBuilder sink = new StringBuilder();
        expressions.setSelectVisitor(new SelectDeParser(expressions, sink));
        expressions.setBuilder(sink);
    }

    private static String qualifier(Column column) {
        return column.getTable() == null || column.getTable().getFullyQualifiedName() == null
                ? "" : column.getTable().getFullyQualifiedName();
    }

    /** Checks every function call in the tree (select items, FROM table functions, WHERE, sub-selects). */
    private static final class CheckingExpressionDeParser extends ExpressionDeParser {
        private final FunctionPolicy policy;
        private final Set<Rejection> found;
        private final Set<String> warnings;

        CheckingExpressionDeParser(FunctionPolicy policy, Set<Rejection> found, Set<String> warnings) {
            this.policy = policy;
            this.found = found;
            this.warnings = warnings;
        }

        @Override
        public <S> StringBuilder visit(Function function, S context) {
            String name = FunctionPolicy.bareName(function.getMultipartName(), function.getName());
            if (policy.isBlocked(name)) {
                found.add(new Rejection(RejectionCode.BLOCKED_FUNCTION, name + " is not allowed"));
            } else if (name.startsWith("pg_sleep")) {
                warnings.add(WARN_PG_SLEEP);
            }
            return super.visit(function, context);
        }
    }

    private static final class CheckingSelectDeParser extends SelectDeParser {
        private final Set<Rejection> found;
        private final Set<String> warnings;

        CheckingSelectDeParser(ExpressionDeParser expressions, StringBuilder sink, Set<Rejection> found,
                Set<String> warnings) {
            super(expressions, sink);
            this.found = found;
            this.warnings = warnings;
        }

        private void checkLocking(Select select) {
            if (select.getForMode() != null) {
                found.add(new Rejection(RejectionCode.LOCKING_CLAUSE,
                        "Locking clause FOR " + select.getForMode().getValue() + " is not read-only."));
            }
        }

        private void checkJoins(PlainSelect plainSelect) {
            List<Join> joins = plainSelect.getJoins();
            if (joins == null) {
                return;
            }
            boolean commaJoin = false;
            for (Join join : joins) {
                if (join.isCross()) {
                    warnings.add(WARN_CROSS_JOIN);
                } else if (join.isSimple()
                        && (join.getOnExpressions() == null || join.getOnExpressions().isEmpty())) {
                    commaJoin = true;
                }
            }
            if (commaJoin && !hasColumnEquality(plainSelect.getWhere())) {
                warnings.add(WARN_COMMA_JOIN);
            }
        }

        @Override
        public <S> StringBuilder visit(PlainSelect plainSelect, S context) {
            checkLocking(plainSelect);
            checkJoins(plainSelect);
            if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                    || plainSelect.getIntoTempTable() != null) {
                found.add(new Rejection(RejectionCode.SELECT_INTO, "SELECT ... INTO creates a table."));
            }
            return super.visit(plainSelect, context);
        }

        @Override
        public <S> StringBuilder visit(SetOperationList list, S context) {
            checkLocking(list);
            return super.visit(list, context);
        }

        @Override
        public <S> StringBuilder visit(ParenthesedSelect select, S context) {
            checkLocking(select);
            return super.visit(select, context);
        }

        @Override
        public <S> StringBuilder visit(WithItem<?> withItem, S context) {
            ParenthesedStatement body = withItem.getParenthesedStatement();
            if (!(body instanceof ParenthesedSelect)) {
                found.add(new Rejection(RejectionCode.DATA_MODIFYING_CTE,
                        "CTE \"" + withItem.getAliasName() + "\" modifies data."));
                return getBuilder();
            }
            return super.visit(withItem, context);
        }
    }
}
