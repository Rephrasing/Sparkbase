package io.github.rephrasing.sparkbase.wrappers;


import com.mongodb.client.MongoCollection;
import io.github.rephrasing.sparkbase.adapters.SparkAdaptersHandler;
import io.github.rephrasing.sparkbase.adapters.SparkDataAdapter;

import io.github.rephrasing.sparkbase.actions.Action;
import io.github.rephrasing.sparkbase.actions.VoidAction;
import lombok.SneakyThrows;
import org.bson.Document;

import javax.annotation.CheckReturnValue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Represents MongoDB collection
 */
public class SparkCollection {

    private final MongoCollection<Document> raw;

    public SparkCollection(MongoCollection<Document> raw) {
        this.raw = raw;
    }

    /**
     * Pushes an object instance to this collection
     * @param instance the instance
     * @param type the instance type
     * @param <T> the instance class type
     */
    @CheckReturnValue
    public <T> VoidAction push(T instance, Class<T> type) {
        return new VoidAction(() -> SparkAdaptersHandler.getInstance().getRatabaseAdapter(type).ifPresentOrElse(adapter -> {
            Document document = adapter.serialize(instance);
            raw.insertOne(document);
        }, () -> {
            throw new IllegalArgumentException("Class type \"" + type.getSimpleName() + "\" is not an implementation of BsonDocumented and does not have a RatabaseAdapter. Cannot be serialized.");
        }));
    }


    /**
     * Uses {@link Predicate} to find and delete any matching database instances before pushing the provided instance
     * @param instance the instance
     * @param type the type of instance
     * @param filter the predicate function
     * @param <T> the instance class type
     */
    @CheckReturnValue
    public <T> VoidAction pushOrReplace(T instance, Class<T> type, Predicate<T> filter) {
        return new VoidAction(() -> {
            SparkAdaptersHandler.getInstance().getRatabaseAdapter(type).ifPresentOrElse(adapter -> {
                Document document = adapter.serialize(instance);
                for (Document doc : raw.find()) {
                    T deserialized = adapter.deserialize(document);
                    if (filter.test(deserialized)) raw.deleteOne(doc);
                }
                raw.insertOne(document);
            }, () -> {
                throw new IllegalArgumentException("Class type \"" + type.getSimpleName() + "\" is not an implementation of BsonDocumented and does not have a RatabaseAdapter. Cannot be serialized.");
            });
        });
    }


    /**
     * Retrieves an object instance from this collection
     * @param type the instance type
     * @param filter the predicate function
     * @return an {@link Optional} that may contain the instance
     * @param <T> the instance class type
     */
    @CheckReturnValue
    public <T> Action<Optional<T>> pull(Class<T> type, Predicate<T> filter) {
        return new Action<>(() -> {
            Optional<SparkDataAdapter<T>> adapterOptional = SparkAdaptersHandler.getInstance().getRatabaseAdapter(type);
            if (adapterOptional.isEmpty())
                throw new IllegalArgumentException("Class type \"" + type.getSimpleName() + "\" does not have a RatabaseAdapter. Cannot be deserialized.");
            for (Document document : raw.find()) {
                T deserialized = adapterOptional.get().deserialize(document);
                if (filter.test(deserialized)) return Optional.of(deserialized);
            }
            return Optional.empty();
        });
    }


    /**
     * Drops a {@link Document} from this collection
     * @param filter the Predicate function
     * @param type the {@link SparkDataAdapter} type
     * @return true if dropped, otherwise false
     * @param <T> the adapter class type
     */
    @CheckReturnValue
    public <T> Action<Boolean> drop(Predicate<Document> filter, Class<T> type) {
        return new Action<>(() -> {
            Optional<SparkDataAdapter<T>> adapterOptional = SparkAdaptersHandler.getInstance().getRatabaseAdapter(type);
            if (adapterOptional.isEmpty())
                throw new IllegalArgumentException("Class type \"" + type.getSimpleName() + "\" does not have a RatabaseAdapter. Cannot be executed.");
            for (Document document : raw.find()) {
                if (filter.test(document)) {
                    raw.findOneAndDelete(document);
                    return true;
                }
            }
            return false;
        });
    }


    /**
     * This method retrieves an object using {@link Predicate} function, executes a {@link Consumer} function upon that retrieved object and finally push it back to the database.
     * @param type the object type
     * @param filter the Predicate function
     * @param function the consumer function
     * @param notFound a failsafe runnable, in case no objects were found.
     * @return true if executed, false if the runnable was executed.
     * @param <T> the object class type
     */
    @SneakyThrows
    @CheckReturnValue
    public <T> Action<Boolean> ifPresentOrElse(Class<T> type, Predicate<T> filter, Consumer<T> function, Runnable notFound) {
        return new Action<>(() -> {
            AtomicBoolean success = new AtomicBoolean(false);
            pull(type, filter).execute().ifPresentOrElse(present -> {
                function.accept(present);
                pushOrReplace(present, type, filter).execute();
                success.set(true);
            }, notFound);
            return success.get();
        });
    }

}