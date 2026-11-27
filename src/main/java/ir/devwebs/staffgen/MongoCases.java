package ir.devwebs.staffgen;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;

import org.bson.Document;
import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Immutable documents use the case UUID as MongoDB's unique primary key. */
final class MongoCases implements CaseStore {
    private final MongoClient client;
    private final MongoCollection<Document> collection;

    MongoCases(ConfigurationSection cfg) {
        String uri = cfg.getString("uri", "mongodb://localhost:27017");
        String database = cfg.getString("database", "staffgen");
        var options =
                MongoClientSettings.builder()
                        .applyToClusterSettings(c -> c.serverSelectionTimeout(10, TimeUnit.SECONDS))
                        .applyToSocketSettings(s -> s.readTimeout(15, TimeUnit.SECONDS))
                        .applyToConnectionPoolSettings(p -> p.maxSize(4))
                        .applyConnectionString(new ConnectionString(uri))
                        .build();
        client = MongoClients.create(options);
        try {
            collection =
                    client.getDatabase(database)
                            .getCollection("staffgen_cases")
                            .withWriteConcern(
                                    WriteConcern.MAJORITY
                                            .withJournal(true)
                                            .withWTimeout(15, TimeUnit.SECONDS));
        } catch (RuntimeException invalid) {
            client.close();
            throw invalid;
        }
    }

    @Override
    public void load(Consumer<CaseBook.Entry> accept) {
        try (MongoCursor<Document> cursor = collection.find().batchSize(256).iterator()) {
            while (cursor.hasNext()) accept.accept(decode(cursor.next()));
        }
    }

    @Override
    public void insert(CaseBook.Entry entry) {
        Document doc = new Document(CaseDocument.fields(entry));
        doc.put("_id", doc.remove("id"));
        collection.insertOne(doc);
    }

    @Override
    public CaseBook.Entry find(UUID id) {
        Document doc = collection.find(Filters.eq("_id", id.toString())).first();
        return doc == null ? null : decode(doc);
    }

    private CaseBook.Entry decode(Document doc) {
        doc.put("id", doc.getString("_id"));
        return CaseDocument.read(doc);
    }

    @Override
    public void close() {
        client.close();
    }
}
