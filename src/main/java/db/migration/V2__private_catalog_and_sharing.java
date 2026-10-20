package db.migration;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve the old catalog while introducing explicit ownership and opt-in access. */
public class V2__private_catalog_and_sharing extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement sql = connection.createStatement()) {
            sql.execute("alter table language_pair add column owner_user_id bigint references app_user(id)");
            // Only a single-account database has an unambiguous legacy owner.
            sql.execute("update language_pair set owner_user_id=(select min(id) from app_user) where (select count(*) from app_user)=1");
        }
        // V1's unnamed global uniqueness constraint must become owner-scoped.
        List<String> constraints = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("select constraint_name from information_schema.table_constraints where table_schema=current_schema() and lower(table_name)='language_pair' and constraint_type='UNIQUE'");
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) constraints.add(rs.getString(1));
        }
        for (String constraint : constraints) {
            List<String> columns = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("select lower(column_name) from information_schema.key_column_usage where constraint_schema=current_schema() and constraint_name=? and lower(table_name)='language_pair' order by ordinal_position")) {
                query.setString(1, constraint);
                try (ResultSet rs = query.executeQuery()) { while (rs.next()) columns.add(rs.getString(1)); }
            }
            if (columns.equals(List.of("source_language", "target_language"))) {
                try (Statement sql = connection.createStatement()) {
                    sql.execute("alter table language_pair drop constraint \"" + constraint.replace("\"", "\"\"") + "\"");
                }
            }
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("alter table language_pair add constraint pair_owner_languages_unique unique(owner_user_id,source_language,target_language)");
            sql.execute("create table pair_share (pair_id bigint not null references language_pair(id) on delete cascade, user_id bigint not null references app_user(id) on delete cascade, primary key(pair_id,user_id))");
            sql.execute("create index shares_by_user on pair_share(user_id,pair_id)");
            // Old tickets must not survive the change in catalog permissions.
            sql.execute("delete from review_ticket");
        }
    }
}
