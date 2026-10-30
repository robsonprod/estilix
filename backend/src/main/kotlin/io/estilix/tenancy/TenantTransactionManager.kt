package io.estilix.tenancy

import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.sql.Connection
import javax.sql.DataSource

/**
 * The single choke point of tenant isolation (design D2): right after `BEGIN`, it copies the current
 * [RequestContext] into transaction-local PostgreSQL settings that the RLS policies read.
 *
 * `set_config(..., true)` is local to the transaction, so the values vanish at COMMIT/ROLLBACK and a
 * pooled connection can never carry one salon into the next request.
 */
class TenantTransactionManager(dataSource: DataSource) : DataSourceTransactionManager(dataSource) {

    init {
        isEnforceReadOnly = true // @Transactional(readOnly = true) becomes SET TRANSACTION READ ONLY
    }

    override fun prepareTransactionalConnection(con: Connection, definition: TransactionDefinition) {
        super.prepareTransactionalConnection(con, definition)
        val ctx = TenantContext.current()
        con.prepareStatement(
            """
            select set_config('app.tenant_id', ?, true),
                   set_config('app.user_id', ?, true),
                   set_config('app.subject', ?, true),
                   set_config('app.support', ?, true)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, ctx.tenantId?.toString() ?: "")
            st.setString(2, ctx.userId?.toString() ?: "")
            st.setString(3, ctx.subject ?: "")
            st.setString(4, if (ctx.support) "on" else "off")
            st.execute()
        }
        // Support reads other salons' data; the database itself refuses writes unless a support
        // use case explicitly asked for them.
        if (ctx.support && !ctx.allowWrites && !definition.isReadOnly) {
            con.createStatement().use { it.execute("set transaction read only") }
        }
    }
}
