/*
 * Copyright 2021-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.shared.Exceptions;
import org.jdbi.v3.core.Handle;
import org.postgresql.util.PSQLException;

import java.util.*;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public final class PostgresqlUtil {
    /**
     * Read the major Postgresql server version
     *
     * @param handle the jdbi handle that will be used for querying
     * @return the major version (12, 13, 14, 15, etc.)
     */
    public static int getServiceMajorVersion(Handle handle) {
        requireNonNull(handle, "No handle provided");
        // version() returns something similar to "PostgreSQL 13.4 on x86_64..."
        return handle.createQuery("SELECT substring(version() from 'PostgreSQL ([0-9]+)')")
                     .mapTo(Integer.class)
                     .first();
    }

    /**
     * Checks if a specified PostgreSQL extension is available in the current database instance.
     *
     * @param handle   the Jdbi {@code Handle} used to execute the query; must not be null
     * @param extension the name of the PostgreSQL extension to check; must not be null
     * @return {@code true} if the specified extension is available, {@code false} otherwise
     */
    public static boolean isPGExtensionAvailable(Handle handle, String extension) {
        requireNonNull(handle, "No handle provided");
        requireNonNull(extension, "No extension provided");
        return handle.createQuery("""
                                    SELECT exists(
                                        SELECT 1
                                        FROM pg_extension
                                        WHERE extname = :extension
                                    );
                """)
                .bind("extension", extension)
                .mapTo(Boolean.class)
                .first();
    }

    /**
     * Determines whether the given exception corresponds to a PostgreSQL extension
     * not being loaded as required by the `shared_preload_libraries` PostgreSQL configuration.
     *
     * @param e the exception to analyze; must not be null
     * @return true if the root cause of the exception indicates that a PostgreSQL extension
     *         must be loaded via `shared_preload_libraries`, false otherwise
     * @throws IllegalArgumentException if the provided exception is null
     */
    public static boolean isPGExtensionNotLoadedException(Exception e) {
        requireNonNull(e, "No exception provided");
        Throwable rootCause = Exceptions.getRootCause(e);
        return rootCause instanceof PSQLException && rootCause.getMessage() != null && rootCause.getMessage().contains("must be loaded via \"shared_preload_libraries\"");
    }

    /**
     * Matches strings that:
     * - Starts with a letter (either uppercase or lowercase) or an underscore.
     * - Followed by zero or more letters (either uppercase or lowercase), digits, or underscores.
     * - The entire string must match this pattern from start to end.
     */
    private static final Pattern VALID_SQL_TABLE_AND_COLUMN_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * This list incorporates a broad range of reserved names, including those specific to PostgreSQL as well as standard SQL keywords, that cannot
     * be used as COLUMN, TABLE and INDEX names.
     * Developers should use this list cautiously and always cross-reference against the current version of PostgreSQL they are working with,
     * as database systems frequently update their list of reserved keywords.<br>
     * <br>
     * The primary goal of this list is to avoid naming conflicts and ensure compatibility with SQL syntax, in an attempt to reduce errors
     * and potential SQL injection vulnerabilities.
     */
    public static final Set<String> RESERVED_NAMES = Set.of(
            // Data Types from "Table 8.1. Data Types" on https://www.postgresql.org/docs/current/datatype.html (excluding TIMESTAMP as this is used by the EventStore)
            "BIGINT", "INT8", "BIGSERIAL", "SERIAL8", "BIT", "VARBIT", "BOOLEAN", "BOOL",
            "BOX", "BYTEA", "CHARACTER", "CHAR", "VARYING", "VARCHAR", "CIDR",
            "CIRCLE", "DATE", "DOUBLE", "PRECISION", "FLOAT8", "INET", "INTEGER", "INT", "INT4",
            "INTERVAL", "JSON", "JSONB", "LINE", "LSEG", "MACADDR", "MACADDR8", "MONEY",
            "NUMERIC", "DECIMAL", "PATH", "PG_LSN", "POINT", "POLYGON", "REAL", "FLOAT4",
            "SMALLINT", "INT2", "SMALLSERIAL", "SERIAL2", "SERIAL", "SERIAL4", "TEXT",
            "TIME", "TIMETZ", "TIMESTAMPTZ", "TSQUERY", "TSVECTOR",
            "TXID_SNAPSHOT", "UUID", "XML",

            // Reserved Keywords from "Table C.1. SQL Key Words" on https://www.postgresql.org/docs/current/sql-keywords-appendix.html
            // where the "PostgreSQL" column specifies "reserved"
            "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC", "ASYMMETRIC",
            "AUTHORIZATION", "BINARY", "BOTH", "CASE", "CAST", "CHECK", "COLLATE",
            "COLLATION", "COLUMN", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_CATALOG",
            "CURRENT_DATE", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "DEFAULT", "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END", "EXCEPT",
            "FALSE", "FETCH", "FOR", "FOREIGN", "FREEZE", "FROM", "FULL", "GRANT", "GROUP",
            "HAVING", "ILIKE", "IN", "INITIALLY", "INNER", "INTERSECT", "INTO", "IS",
            "ISNULL", "JOIN", "LEADING", "LEFT", "LIKE", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP",
            "NATURAL", "NOT", "NOTNULL", "NULL", "OFFSET", "ON", "ONLY", "OR", "ORDER",
            "OUTER", "OVERLAPS", "PLACING", "PRIMARY", "REFERENCES", "RETURNING", "RIGHT",
            "SELECT", "SESSION_USER", "SIMILAR", "SOME", "SYMMETRIC", "TABLE", "THEN",
            "TO", "TRAILING", "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC",
            "VERBOSE", "WHEN", "WHERE", "WINDOW", "WITH",

            // Additional
            "DROP", "EXISTS", "EXPLAIN",
            "CLOB", "BLOB", "NBLOB", "NCHAR",
            "SAVEPOINT", "TIMESTAMPZ",
            "VACUUM",  "VIEW",

            // Reserved Keywords  "Table C.1. SQL Key Words" on https://www.postgresql.org/docs/current/sql-keywords-appendix.html where
            // the "SQL:2023", "SQL:2016" or "SQL-92" columns  specifies "reserved
            "ABS", "ALLOCATE", "ALTER", "ARE", "ASENSITIVE", "AT", "ATOMIC", "BEGIN",
            "BETWEEN", "CALL", "CALLED", "CEIL", "CEILING", "CLOSE", "COALESCE", "COMMIT",
            "CONNECT", "CONNECTION", "CONVERT", "CORR", "CORRESPONDING", "COUNT", "COVAR_POP",
            "COVAR_SAMP", "CUBE", "CUME_DIST", "CURRENT", "CURRENT_DEFAULT_TRANSFORM_GROUP",
            "CURRENT_PATH", "CURRENT_ROW", "CURRENT_TRANSFORM_GROUP_FOR_TYPE", "CURSOR", "CYCLE",
            "DAY", "DEALLOCATE", "DECLARE", "DELETE", "DENSE_RANK", "DEREF", "DESCRIBE",
            "DETERMINISTIC", "DISCONNECT", "END-EXEC", "ESCAPE", "EVERY", "EXEC", "EXCEPTION", "EXECUTE",
            "EXIT", "EXP", "EXTERNAL", "EXTRACT", "FILTER", "FIRST", "FLOOR", "FOUND",
            "FUNCTION", "FUSION", "GET", "GLOBAL", "GROUPING", "HOLD", "HOUR",
            "IDENTITY", "IMMEDIATE", "INDICATOR", "INOUT", "INPUT", "INSENSITIVE", "INSERT",
            "KEY", "LAG", "LANGUAGE", "LARGE", "LAST", "LATERAL", "LEAD",
            "LEVEL", "LOCAL", "MATCH", "MAX", "MEMBER", "MERGE", "METHOD", "MIN", "MINUTE",
            "MOD", "MODIFIES", "MODULE", "MONTH", "MULTISET", "NCLOB", "NEW", "NO", "NONE",
            "NORMALIZE", "NULLIF", "OBJECT", "OCCURRENCES_REGEX", "OCTETS", "OF", "OLD",
            "OPEN", "OPERATION", "OPTIONS", "ORDINALITY", "OUT", "OUTPUT", "OVER", "OVERLAY",
            "PAD", "PARAMETER", "PARTITION", "PERCENT", "PERCENT_RANK", "PERCENTILE_CONT",
            "PERCENTILE_DISC", "POSITION", "POWER", "PRECEDING", "PREPARE",
            "PROCEDURE", "RANGE", "RANK", "READS", "RECURSIVE", "REF", "REFERENCING",
            "REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE",
            "REGR_SXX", "REGR_SXY", "REGR_SYY", "RELATIVE", "RELEASE", "REPEAT", "RESIGNAL",
            "RESTRICT", "RESULT", "RETURN", "RETURNS", "REVOKE", "ROLE", "ROLLUP", "ROW",
            "ROW_NUMBER", "ROWS", "SCOPE", "SCROLL", "SEARCH", "SECOND", "SECTION", "SENSITIVE",
            "SET", "SIGNAL","SPECIFIC", "SPECIFICTYPE", "SQL", "SQLEXCEPTION",
            "SQLSTATE", "SQLWARNING", "SQRT", "STACKED", "START", "STATIC", "STDDEV_POP",
            "STDDEV_SAMP", "SUBSTRING", "SUM", "SYSTEM", "SYSTEM_USER", "TABLESAMPLE",
            "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TRANSLATE",
            "TRANSLATE_REGEX", "TRANSLATION", "TREAT", "TRIGGER", "TRIM", "UESCAPE",
            "UNBOUNDED", "UNKNOWN", "UNNEST", "UNTIL", "UPDATE", "VALUE", "VALUES",
            "VAR_POP", "VAR_SAMP", "VARBINARY", "WIDTH_BUCKET", "WITHIN", "WITHOUT",
            "WORK", "WRITE", "XMLATTRIBUTES", "XMLBINARY", "XMLCAST", "XMLCOMMENT",
            "XMLCONCAT", "XMLELEMENT", "XMLEXISTS", "XMLFOREST", "XMLITERATE", "XMLNAMESPACES",
            "XMLPARSE", "XMLPI", "XMLQUERY", "XMLROOT", "XMLSCHEMA", "XMLSERIALIZE", "XMLTABLE",
            "YEAR", "ZONE");

            /**
             * Validates whether the provided table or column name is valid according to PostgreSQL naming conventions
             * and does not conflict with reserved keywords.<br>
             * <br>
             * The method provided is designed as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
             * However, Essentials components as well as {@link PostgresqlUtil#checkIsValidTableOrColumnName(String, String)} does not offer exhaustive protection, nor does it assure
             * the complete security of the resulting SQL against SQL injection threats.<br>
             * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.<br>
             * Users must ensure thorough sanitization and validation of API input parameters,  column, table, and index names.<br>
             * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
             * <p>
             * The method checks if the {@code tableOrColumnName}:
             * <ul>
             *     <li>Is not null, empty, and does not consist solely of whitespace.</li>
             *     <li>Does not match any PostgreSQL reserved keyword (case-insensitive check).</li>
             *     <li>Contains only characters valid for PostgreSQL identifiers: letters, digits, and underscores,
             *         and does not start with a digit.</li>
             * </ul>
             * <p>
             *
             * @param tableOrColumnName the table or column name to validate.
             * @param context           optional context that will be included in any error message. null value means no context is provided
             * @throws InvalidTableOrColumnNameException if the provided name is null, empty, matches a reserved keyword,
             *                                           or contains invalid characters.
             */

    public static void checkIsValidTableOrColumnName(String tableOrColumnName, String context) {
        if (tableOrColumnName == null || tableOrColumnName.trim().isEmpty()) {
            throw new InvalidTableOrColumnNameException("Table or column name cannot be null or empty.");
        }

        // Check against reserved keywords
        String upperCaseName = tableOrColumnName.toUpperCase().trim();
        if (RESERVED_NAMES.contains(upperCaseName)) {
            throw new InvalidTableOrColumnNameException(msg("The name '{}'{} is a reserved keyword and cannot be used as a table or column name.", tableOrColumnName, context != null ? (" in context: " + context) : ""));
        }

        // Validate characters in the name
        if (!VALID_SQL_TABLE_AND_COLUMN_NAME_PATTERN.matcher(tableOrColumnName).matches()) {
            throw new InvalidTableOrColumnNameException(msg("Invalid table or column name: '{}'{}. Names must start with a letter or underscore, followed by letters, digits, or underscores.",
                                                            tableOrColumnName, context != null ? (" in context: " + context) : ""));
        }
    }


    /**
     * Validates whether the provided table or column name is valid according to PostgreSQL naming conventions
     * and does not conflict with reserved keywords.<br>
     * This method calls {@link #checkIsValidTableOrColumnName(String, String)} with a null context.<br>
     * <br>
     * The method provided is designed as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
     * However, Essentials components as well as {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} does not offer exhaustive protection, nor does it assure the complete security of the resulting
     * SQL against SQL injection threats.<br>
     * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.<br>
     * Users must ensure thorough sanitization and validation of API input parameters,  column, table, and index names.<br>
     * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
     * <p>
     * The method checks if the {@code tableOrColumnName}:
     * <ul>
     *     <li>Is not null, empty, and does not consist solely of whitespace.</li>
     *     <li>Does not match any PostgreSQL reserved keyword (case-insensitive check).</li>
     *     <li>Contains only characters valid for PostgreSQL identifiers: letters, digits, and underscores,
     *         and does not start with a digit.</li>
     * </ul>
     * <p>
     *
     * @param tableOrColumnName the table or column name to validate.
     * @throws InvalidTableOrColumnNameException if the provided name is null, empty, matches a reserved keyword,
     *                                           or contains invalid characters.
     */
    public static void checkIsValidTableOrColumnName(String tableOrColumnName) {
        checkIsValidTableOrColumnName(tableOrColumnName, null);
    }
 
    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    
    /**
     * A compiled regex pattern used to validate the format of SQL function names.
     * The pattern enforces the following rules:
     * 1. The name must start with a letter (a-z or A-Z) or an underscore (_).
     * 2. Subsequent characters can include letters, digits (0-9), or underscores (_).
     * 3. The length of the name must not exceed 63 characters.
     *
     * This pattern is designed to ensure compliance with SQL naming conventions
     * and avoid potential conflicts with system or reserved identifiers.
     */
    public static final Pattern FN_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    /**
     * A compiled regex pattern used to validate the format of fully qualified SQL function names.
     * The pattern enforces the following rules:
     * 1. The name must consist of two parts separated by a dot ('.').
     * 2. Each part must start with a letter (a-z or A-Z) or an underscore (_).
     * 3. Each part can contain letters, digits (0-9), or underscores (_) after the initial character.
     * 4. Each part must not exceed 63 characters in length.
     *
     * This pattern ensures that the function name adheres to SQL naming conventions,
     * including support for fully qualified names in the format of `schema_name.function_name`.
     */
    public static final Pattern QUALIFIED_FN_NAME =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}\\.[a-zA-Z_][a-zA-Z0-9_]{0,62}$");


    /**
     * Validates whether the given string is a valid SQL function name.
     *
     * <p>The method enforces PostgreSQL SQL naming conventions for function names. A valid function name:
     * <ul>
     *     <li>Must not be null, empty, or consist only of whitespace.</li>
     *     <li>Must match the pattern {@link PostgresqlUtil#FN_NAME} for non-qualified function names
     *         or {@link PostgresqlUtil#QUALIFIED_FN_NAME} for fully qualified function names
     *         (e.g., <code>schema_name.function_name</code>).</li>
     *     <li>Must not contain any reserved keywords defined in {@link PostgresqlUtil#RESERVED_NAMES}.</li>
     * </ul>
     *
     * @param functionName The name of the SQL function to validate, either fully qualified or unqualified.
     * @return {@code true} if the provided {@code functionName} is valid according to PostgreSQL naming conventions
     *         and does not contain reserved keywords; {@code false} otherwise.
     *
     * <p>Usage example:
     * <pre>
     * {@code
     * boolean isValid = PostgresqlUtil.isValidFunctionName("my_schema.my_function");
     * // Returns true if "my_schema.my_function" conforms to SQL conventions and contains no reserved keywords.
     * }
     * </pre>
     */
    public static boolean isValidFunctionName(String functionName) {
        if (functionName == null || functionName.trim().isEmpty()) {
            return false;
        }

        // Qualified function name?
        if (functionName.contains(".")) {
            if (!QUALIFIED_FN_NAME.matcher(functionName).matches()) {
                return false;
            }

            var parts = functionName.split("\\.");
            for (var part : parts) {
                if (RESERVED_NAMES.contains(part.toUpperCase(Locale.ROOT).trim())) {
                    return false;
                }
            }

            return true;
        } else {
            if (!FN_NAME.matcher(functionName).matches()) {
                return false;
            }

            return !RESERVED_NAMES.contains(functionName.toUpperCase(Locale.ROOT).trim());
        }
    }
}
