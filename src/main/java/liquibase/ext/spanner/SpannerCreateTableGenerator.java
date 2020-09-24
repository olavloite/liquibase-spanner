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

import java.util.Iterator;

public class SpannerCreateTableGenerator extends CreateTableGenerator {

    public SpannerCreateTableGenerator() {
    }

    //
    // Reference
    // https://cloud.google.com/spanner/docs/data-definition-language#ddl_syntax
    //
    @Override
    public Sql[] generateSql(CreateTableStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {

        // TODO: no CatalogName supported. No SchemaName supported.
        StringBuilder buffer = new StringBuilder();
        buffer.append("CREATE TABLE ").append(database.escapeTableName(statement.getCatalogName(),
                statement.getSchemaName(), statement.getTableName())).append(" ");
        buffer.append("(");

        // We have reached the point after "CREATE TABLE ... (" and will now iterate through the column list.
        final Iterator<String> columnIterator = statement.getColumns().iterator();
        while (columnIterator.hasNext()) {
            final String column = columnIterator.next();

            // Column name
            final String columnName = database.escapeColumnName(
                    statement.getCatalogName(), statement.getSchemaName(),
                    statement.getTableName(), column);
            buffer.append(columnName);

            // Column type
            final DatabaseDataType columnType = statement.getColumnTypes().get(column).toDatabaseDataType(database);
            buffer.append(" ").append(columnType);

            // NOT NULL if applicable
            if (statement.getNotNullColumns().containsKey(column)) {
                buffer.append(" NOT NULL");
            }

            // Auto commit timestamp is the (rough) equivalent of auto increment
            for (AutoIncrementConstraint iter : statement.getAutoIncrementConstraints()) {
                if (!iter.getColumnName().equals(column)) {
                    continue;
                }
                buffer.append(" OPTIONS ( allow_commit_timestamp = true )");
            }

            // Comma for next column (if any)
            if (columnIterator.hasNext()) {
                buffer.append(", ");
            }
        }

        // End of columns
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

        // Primary key
        if ((statement.getPrimaryKeyConstraint() != null) && !statement.getPrimaryKeyConstraint().getColumns()
                .isEmpty()) {
            buffer.append(" PRIMARY KEY (");
            buffer.append(database.escapeColumnNameList(StringUtils.join(statement.getPrimaryKeyConstraint().getColumns(), ", ")));
            buffer.append(")");
        }

        // Return create statement
        return new Sql[]{ new UnparsedSql(buffer.toString(), getAffectedTable(statement)) };
    }

    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }
}
