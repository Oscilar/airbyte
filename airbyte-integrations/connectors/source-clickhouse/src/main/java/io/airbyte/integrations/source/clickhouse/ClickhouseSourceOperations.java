package io.airbyte.integrations.source.clickhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.airbyte.db.SourceOperations;
import io.airbyte.db.jdbc.AbstractJdbcCompatibleSourceOperations;
import io.airbyte.db.jdbc.JdbcSourceOperations;
import io.airbyte.protocol.models.JsonSchemaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;

import static io.airbyte.db.jdbc.JdbcConstants.INTERNAL_COLUMN_NAME;
import static io.airbyte.db.jdbc.JdbcConstants.INTERNAL_COLUMN_TYPE;
import static io.airbyte.db.jdbc.JdbcConstants.INTERNAL_SCHEMA_NAME;
import static io.airbyte.db.jdbc.JdbcConstants.INTERNAL_TABLE_NAME;
import static io.airbyte.db.jdbc.JdbcUtils.ALLOWED_CURSOR_TYPES;

public class ClickhouseSourceOperations extends AbstractJdbcCompatibleSourceOperations<JDBCType> implements SourceOperations<ResultSet, JDBCType> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSourceOperations.class);
    private static final ObjectMapper mapper =  new ObjectMapper().registerModule(new JavaTimeModule());

    protected JDBCType safeGetJdbcType(final int columnTypeInt) {
        try {
            return JDBCType.valueOf(columnTypeInt);
        } catch (final Exception e) {
            return JDBCType.VARCHAR;
        }
    }

    @Override
    public void copyToJsonField(final ResultSet resultSet, final int colIndex, final ObjectNode json) throws SQLException {
        final int columnTypeInt = resultSet.getMetaData().getColumnType(colIndex);
        final String columnName = resultSet.getMetaData().getColumnName(colIndex);
        final JDBCType columnType = safeGetJdbcType(columnTypeInt);

        switch (columnType) {
            case BIT, BOOLEAN -> putBoolean(json, columnName, resultSet, colIndex);
            case TINYINT, SMALLINT -> putShortInt(json, columnName, resultSet, colIndex);
            case INTEGER -> putInteger(json, columnName, resultSet, colIndex);
            case BIGINT -> putBigInt(json, columnName, resultSet, colIndex);
            case FLOAT, DOUBLE -> putDouble(json, columnName, resultSet, colIndex);
            case REAL -> putFloat(json, columnName, resultSet, colIndex);
            case NUMERIC, DECIMAL -> putBigDecimal(json, columnName, resultSet, colIndex);
            case CHAR, VARCHAR, LONGVARCHAR -> putString(json, columnName, resultSet, colIndex);
            case DATE -> putDate(json, columnName, resultSet, colIndex);
            case TIME -> putTime(json, columnName, resultSet, colIndex);
            case TIMESTAMP -> putTimestamp(json, columnName, resultSet, colIndex);
            case TIMESTAMP_WITH_TIMEZONE -> putTimestampWithTimezone(json, columnName, resultSet, colIndex);
            case BLOB, BINARY, VARBINARY, LONGVARBINARY -> putBinary(json, columnName, resultSet, colIndex);
            case ARRAY -> putArray(json, columnName, resultSet, colIndex);
            default -> putDefault(json, columnName, resultSet, colIndex);
        }
    }

    @Override
    public void setCursorField(final PreparedStatement preparedStatement,
                               final int parameterIndex,
                               final JDBCType cursorFieldType,
                               final String value)
            throws SQLException {
        switch (cursorFieldType) {

            case TIMESTAMP -> setTimestamp(preparedStatement, parameterIndex, value);
            case TIMESTAMP_WITH_TIMEZONE -> setTimestampWithTimezone(preparedStatement, parameterIndex, value);
            case TIME -> setTime(preparedStatement, parameterIndex, value);
            case TIME_WITH_TIMEZONE -> setTimeWithTimezone(preparedStatement, parameterIndex, value);
            case DATE -> setDate(preparedStatement, parameterIndex, value);
            case BIT -> setBit(preparedStatement, parameterIndex, value);
            case BOOLEAN -> setBoolean(preparedStatement, parameterIndex, value);
            case TINYINT, SMALLINT -> setShortInt(preparedStatement, parameterIndex, value);
            case INTEGER -> setInteger(preparedStatement, parameterIndex, value);
            case BIGINT -> setBigInteger(preparedStatement, parameterIndex, value);
            case FLOAT, DOUBLE -> setDouble(preparedStatement, parameterIndex, value);
            case REAL -> setReal(preparedStatement, parameterIndex, value);
            case NUMERIC, DECIMAL -> setDecimal(preparedStatement, parameterIndex, value);
            case CHAR, NCHAR, NVARCHAR, VARCHAR, LONGVARCHAR -> setString(preparedStatement, parameterIndex, value);
            case BINARY, BLOB -> setBinary(preparedStatement, parameterIndex, value);
            // since cursor are expected to be comparable, handle cursor typing strictly and error on
            // unrecognized types
            default -> throw new IllegalArgumentException(String.format("%s cannot be used as a cursor.", cursorFieldType));
        }
    }

    protected void setTimestampWithTimezone(final PreparedStatement preparedStatement, final int parameterIndex, final String value)
            throws SQLException {
        try {
            preparedStatement.setObject(parameterIndex, OffsetDateTime.parse(value));
        } catch (final DateTimeParseException e) {
            throw new RuntimeException(e);
        }
    }

    protected void setTimeWithTimezone(final PreparedStatement preparedStatement, final int parameterIndex, final String value) throws SQLException {
        try {
            preparedStatement.setObject(parameterIndex, OffsetTime.parse(value));
        } catch (final DateTimeParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JDBCType getDatabaseFieldType(final JsonNode field) {
        try {
            return JDBCType.valueOf(field.get(INTERNAL_COLUMN_TYPE).asInt());
        } catch (final IllegalArgumentException ex) {
            LOGGER.warn(String.format("Could not convert column: %s from table: %s.%s with type: %s. Casting to VARCHAR.",
                    field.get(INTERNAL_COLUMN_NAME),
                    field.get(INTERNAL_SCHEMA_NAME),
                    field.get(INTERNAL_TABLE_NAME),
                    field.get(INTERNAL_COLUMN_TYPE)));
            return JDBCType.VARCHAR;
        }
    }

    @Override
    public boolean isCursorType(final JDBCType type) {
        return ALLOWED_CURSOR_TYPES.contains(type);
    }

    @Override
    public JsonSchemaType getAirbyteType(final JDBCType jdbcType) {
        return switch (jdbcType) {
            case BIT, BOOLEAN -> JsonSchemaType.BOOLEAN;
            case TINYINT, SMALLINT, BIGINT, INTEGER -> JsonSchemaType.INTEGER;
            case FLOAT, DOUBLE, REAL, NUMERIC, DECIMAL -> JsonSchemaType.NUMBER;
            case CHAR, NCHAR, NVARCHAR, VARCHAR, LONGVARCHAR, TIMESTAMP, TIME, DATE -> JsonSchemaType.STRING;
            case BLOB, BINARY, VARBINARY, LONGVARBINARY -> JsonSchemaType.STRING_BASE_64;
            case ARRAY -> JsonSchemaType.ARRAY;
            // since column types aren't necessarily meaningful to Airbyte, liberally convert all unrecgonised
            // types to String
            default -> JsonSchemaType.STRING;
        };
    }

    @Override
    protected void putArray(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) throws SQLException {
        final ArrayNode arrayNode = mapper.createArrayNode();
        try {
            final ResultSet arrayResultSet = resultSet.getArray(index).getResultSet();
            while (arrayResultSet.next()) {
                arrayNode.add(arrayResultSet.getString(2));
            }
            node.set(columnName, arrayNode);
        } catch (final SQLException e) {
            node.set(columnName, mapper.valueToTree(resultSet.getObject(index)));
        }
    }
}