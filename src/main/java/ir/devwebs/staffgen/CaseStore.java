package ir.devwebs.staffgen;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persistence boundary for one server's case ledger. Records are immutable and keyed by UUID.
 * Providers must put a unique constraint on the identifier. CaseBook serializes live writes.
 */
interface CaseStore extends AutoCloseable {
    void load(Consumer<CaseBook.Entry> accept) throws Exception;

    void insert(CaseBook.Entry entry) throws Exception;

    CaseBook.Entry find(UUID id) throws Exception;

    default void close() throws Exception {}
}
