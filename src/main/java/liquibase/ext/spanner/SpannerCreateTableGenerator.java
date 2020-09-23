package liquibase.ext.spanner;

import liquibase.database.Database;
import liquibase.datatype.DatabaseDataType;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.CreateTableGenerator;
import liquibase.statement.AutoIncrementConstraint;
import liquibase.statement.ForeignKeyConstraint;
import liquibase.statement.core.CreateTableStatement;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SpannerCreateTableGenerator extends CreateTableGenerator {

    final Logger log = LoggerFactory.getLogger(SpannerCreateTableGenerator.class);

    public SpannerCreateTableGenerator() {
        log.info("Starting create table generator");
    }

    @Override
    public Sql[] generateSql(CreateTableStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        log.info("Generating sql");

        List<Sql> additionalSql = new ArrayList<>();

        StringBuilder buffer = new StringBuilder();
        buffer.append("CREATE TABLE ").append(database.escapeTableName(statement.getCatalogName(),
                statement.getSchemaName(), statement.getTableName())).append(" ");
        buffer.append("(");

        boolean isSinglePrimaryKeyColumn = (statement.getPrimaryKeyConstraint() != null) && (statement
                .getPrimaryKeyConstraint().getColumns().size() == 1);

        boolean isPrimaryKeyAutoIncrement = false;

        /* We have reached the point after "CREATE TABLE ... (" and will now iterate through the column list. */
        Iterator<String> columnIterator = statement.getColumns().iterator();
        while (columnIterator.hasNext()) {
            String column = columnIterator.next();

            // Column name
            buffer.append(database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName(), column));

            // Column type
            DatabaseDataType columnType = statement.getColumnTypes().get(column).toDatabaseDataType(database);
            buffer.append(" ").append(columnType);

            // TODO: This is an error. No auto incremental constraints.
            AutoIncrementConstraint autoIncrementConstraint = null;
            for (AutoIncrementConstraint currentAutoIncrementConstraint : statement.getAutoIncrementConstraints()) {
                if (column.equals(currentAutoIncrementConstraint.getColumnName())) {
                    autoIncrementConstraint = currentAutoIncrementConstraint;
                    break;
                }
            }

            // NOT NULL if applicable
            if (statement.getNotNullColumns().get(column) != null) {
                buffer.append(" NOT NULL");
            }

            // TODO: Add in allow_commit_timestamp option.
            if (columnIterator.hasNext()) {
                buffer.append(", ");
            }
        }

        buffer.append(")");

        Iterator<ForeignKeyConstraint> fkConstraintIter = statement.getForeignKeyConstraints().iterator();
        while (fkConstraintIter.hasNext()) {
            ForeignKeyConstraint fkConstraint = fkConstraintIter.next();

            String referencesString = fkConstraint.getReferences();

            buffer.append(" FOREIGN KEY (")
                    .append(database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName(), fkConstraint.getColumn()))
                    .append(") REFERENCES ");

            if (referencesString != null) {
                if (!referencesString.contains(".") && (database.getDefaultSchemaName() != null) && database
                        .getOutputDefaultSchema()) {
                    referencesString = database.escapeObjectName(database.getDefaultSchemaName(), Schema.class) + "." + referencesString;
                }
                buffer.append(referencesString);
            } else {
                buffer.append(database.escapeObjectName(fkConstraint.getReferencedTableCatalogName(), fkConstraint.getReferencedTableSchemaName(), fkConstraint.getReferencedTableName(), Table.class))
                        .append("(")
                        .append(database.escapeColumnNameList(fkConstraint.getReferencedColumnNames()))
                        .append(")");

            }

            if (fkConstraintIter.hasNext()) {
                buffer.append(",");
            }
        }

        /*
         * Here, the list of columns and constraints in the form
         * ( column1, ..., columnN, constraint1, ..., constraintN,
         * ends. We cannot leave an expression like ", )", so we remove the last comma.
         */

        // Primary key
        if ((statement.getPrimaryKeyConstraint() != null) && !statement.getPrimaryKeyConstraint().getColumns()
                .isEmpty()) {
            buffer.append(" PRIMARY KEY (");
            buffer.append(database.escapeColumnNameList(StringUtils.join(statement.getPrimaryKeyConstraint().getColumns(), ", ")));
            buffer.append(")");
        }
        //String sql = buffer.toString().replaceFirst(",\\s*$", "") + ")";


        additionalSql.add(0, new UnparsedSql(buffer.toString(), getAffectedTable(statement)));

        return additionalSql.toArray(new Sql[additionalSql.size()]);
    }

    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }
}
