package com.vocabler;

import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@Configuration
class DatabaseConfig {
    @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
        // Preserve the existing JSON field names on databases with lowercase identifiers.
        return new JdbcTemplate(dataSource) {
            @Override protected RowMapper<Map<String, Object>> getColumnMapRowMapper() {
                return new ColumnMapRowMapper() {
                    @Override protected String getColumnKey(String columnName) {
                        return columnName.toUpperCase(Locale.ROOT);
                    }
                };
            }
        };
    }
}
