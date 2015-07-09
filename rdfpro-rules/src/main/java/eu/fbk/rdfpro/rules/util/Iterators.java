package eu.fbk.rdfpro.rules.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Iterators {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iterators.class);

    private static void closeQuietly(final Object object) {
        if (object instanceof AutoCloseable) {
            try {
                ((AutoCloseable) object).close();
            } catch (final Throwable ex) {
                LOGGER.error("Could not close " + object.getClass().getName(), ex);
            }
        }
    }

    @Nullable
    public static <T> Iterator<T> unmodifiable(@Nullable final Iterator<T> iterator) {
        return iterator == null ? null : new UnmodifiableIterator<T>(iterator);
    }

    public static <T> Iterator<T> concat(
            final Iterator<? extends Iterator<? extends T>> iteratorSupplier) {
        return new ConcatIterator<T>(Objects.requireNonNull(iteratorSupplier));
    }

    public static <T, R extends T> Iterator<T> filter(final Iterator<R> iterator,
            final Predicate<? super R> predicate) {
        // TODO
        return null;
    }

    public static <T, R> Iterator<R> transform(final Iterator<T> iterator,
            final Function<? super T, ? extends R> transformer) {
        return new TransformIterator<T, R>(Objects.requireNonNull(iterator),
                Objects.requireNonNull(transformer));
    }

    private static final class UnmodifiableIterator<T> implements Iterator<T>, AutoCloseable {

        private final Iterator<T> iterator;

        UnmodifiableIterator(final Iterator<T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return this.iterator.hasNext();
        }

        @Override
        public T next() {
            return this.iterator.next();
        }

        @Override
        public void close() {
            if (this.iterator instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) this.iterator).close();
                } catch (final Throwable ex) {
                    LOGGER.error("Could not close iterator", ex);
                }
            }
        }

    }

    private static final class ConcatIterator<T> implements Iterator<T>, AutoCloseable {

        private final Iterator<? extends Iterator<? extends T>> iteratorSupplier;

        @Nullable
        private Iterator<? extends T> currentIterator;

        @Nullable
        private Iterator<? extends T> removeIterator;

        private boolean eof;

        ConcatIterator(final Iterator<? extends Iterator<? extends T>> iteratorSupplier) {
            this.iteratorSupplier = iteratorSupplier;
            this.currentIterator = null;
            this.removeIterator = null;
            this.eof = false;
        }

        @Override
        public boolean hasNext() {
            if (this.eof) {
                return false;
            }
            while (true) {
                if (this.currentIterator != null) {
                    if (this.currentIterator.hasNext()) {
                        return true;
                    } else if (this.currentIterator != this.removeIterator) {
                        closeQuietly(this.currentIterator);
                    }
                }
                if (!this.iteratorSupplier.hasNext()) {
                    this.eof = true;
                    return false;
                }
                this.currentIterator = this.iteratorSupplier.next();
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final T element = this.currentIterator.next();
            if (this.removeIterator != this.currentIterator) {
                closeQuietly(this.removeIterator);
            }
            this.removeIterator = this.currentIterator;
            return element;
        }

        @Override
        public void remove() {
            if (this.removeIterator == null) {
                throw new NoSuchElementException();
            }
            this.removeIterator.remove();
            this.removeIterator = null;
        }

        @Override
        public void close() throws Exception {
            this.eof = true;
            closeQuietly(this.removeIterator);
            closeQuietly(this.currentIterator);
            closeQuietly(this.iteratorSupplier);
        }

    }

    private static final class TransformIterator<T, R> implements Iterator<R>, AutoCloseable {

        private final Iterator<T> iterator;

        private final Function<? super T, ? extends R> transformer;

        public TransformIterator(final Iterator<T> iterator,
                final Function<? super T, ? extends R> transformer) {
            this.iterator = iterator;
            this.transformer = transformer;
        }

        @Override
        public boolean hasNext() {
            return this.iterator.hasNext();
        }

        @Override
        public R next() {
            return this.transformer.apply(this.iterator.next());
        }

        @Override
        public void remove() {
            this.iterator.remove();
        }

        @Override
        public void close() throws Exception {
            closeQuietly(this.iterator);
        }

    }

}