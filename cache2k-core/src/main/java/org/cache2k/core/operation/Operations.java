package org.cache2k.core.operation;

/*
 * #%L
 * cache2k core
 * %%
 * Copyright (C) 2000 - 2016 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.CacheEntry;
import org.cache2k.expiry.ExpiryTimeValues;
import org.cache2k.processor.EntryProcessingException;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.processor.MutableCacheEntry;
import org.cache2k.processor.RestartException;
import org.cache2k.core.ExceptionWrapper;

/**
 * Semantics of all cache operations on entries.
 *
 * @author Jens Wilke
 */
@SuppressWarnings({"unchecked", "UnusedParameters"})
public class Operations<K, V> {

  public final static Operations SINGLETON = new Operations();

  public Semantic<K, V, V> peek(K key) {
    return PEEK;
  }
  static final Semantic PEEK = new Semantic.Read() {
    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
      }
    }
  };

  public Semantic<K, V, V> get(K key) {
    return GET;
  }

  static final Semantic GET = new Semantic.MightUpdateExisting() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
      } else {
        c.wantMutation();
      }
    }

    @Override
    public void update(Progress c, ExaminationEntry e) {
        /* after the mutation lock is acquired the entry needs to be
           checked again, since other operations may have completed.
           the thundering herd is just stalled in front of the lock.
           however, the general processing scheme calls examine again, after
           the locking, so we can safe the additional code.
        if (e.hasFreshData()) {
          c.finish(e.getValueOrException());
          return;
        }
        */
      c.load();
    }
  };

  public static final Semantic UNCONDITIONAL_LOAD = new Semantic.Update() {
    @Override
    public void update(final Progress c, final ExaminationEntry e) {
      c.load();
    }
  };

  public static final Semantic REFRESH = new Semantic.Update() {
    @Override
    public void update(final Progress c, final ExaminationEntry e) {
      c.refresh();
    }
  };

  public Semantic<K, V, ResultEntry<K,V>> getEntry(K key) {
    return GET_ENTRY;
  }

  static final Semantic GET_ENTRY = new Semantic.MightUpdateExisting() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.entryResult(e);
      } else {
        c.wantMutation();
      }
    }

    @Override
    public void update(final Progress c, final ExaminationEntry e) {
      c.load();
    }

    @Override
    public void loaded(final Progress c, final ExaminationEntry e) {
      c.entryResult(e);
    }
  };

  public Semantic<K, V, ResultEntry<K,V>> peekEntry(K key) {
    return PEEK_ENTRY;
  }

  static final Semantic PEEK_ENTRY = new Semantic.Read() {

    @Override
    public void examine(final Progress c, final ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.entryResult(e);
      }
    }

  };

  public Semantic<K, V, V> remove(K key) {
    return REMOVE;
  }

  static final Semantic REMOVE = new Semantic.Update() {

    @Override
    public void update(Progress c, ExaminationEntry e) {
      c.remove();
    }
  };

  public Semantic<K, V, Boolean> containsAndRemove(K key) {
    return CONTAINS_REMOVE;
  }

  static final Semantic CONTAINS_REMOVE = new Semantic.UpdateExisting() {

    @Override
    public void update(Progress c, ExaminationEntry e) {
      if (c.isPresent()) {
        c.result(true);
        c.remove();
        return;
      }
      c.result(false);
      c.remove();
    }
  };

  public Semantic<K, V, Boolean> contains(K key) {
    return CONTAINS;
  }

  static final Semantic CONTAINS = new Semantic.Read() {

    @Override
    public void examine(Progress c, ExaminationEntry e) {
      if (c.isPresent()) {
        c.result(true);
        return;
      }
      c.result(false);
    }
  };

  public Semantic<K, V, V> peekAndRemove(K key) {
    return PEEK_REMOVE;
  }

  static final Semantic PEEK_REMOVE = new Semantic.UpdateExisting() {

    @Override
    public void update(Progress c, ExaminationEntry e) {
      if (c.isPresentOrMiss()) {
        c.result(e.getValueOrException());
      }
      c.remove();
    }
  };

  public Semantic<K, V, V> peekAndReplace(final K key, final V value) {
    return new Semantic.UpdateExisting<K, V, V>() {

      @Override
      public void update(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(e.getValueOrException());
          c.put(value);
        }
      }

    };
  }

  public Semantic<K, V, V> peekAndPut(final K key, final V value) {
    return new Semantic.UpdateExisting<K, V, V>() {

      @Override
      public void update(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(e.getValueOrException());
        }
        c.put(value);
      }

    };
  }

  public Semantic<K, V, V> put(final K key, final V value) {
    return new Semantic.Update<K, V, V>() {

      @Override
      public void update(Progress<K, V, V> c, ExaminationEntry<K, V> e) {
        c.put(value);
      }

    };
  }

  /**
   * Updates intentionally hit and miss counter to adjust with JSR107.
   *
   * @see org.cache2k.Cache#putIfAbsent(Object, Object)
   */
  public Semantic<K, V, Boolean> putIfAbsent(final K key, final V value) {
    return new Semantic.UpdateExisting<K, V, Boolean>() {

      @Override
      public void update(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        if (!c.isPresentOrMiss()) {
          c.result(true);
          c.put(value);
          return;
        }
        c.result(false);
      }

    };
  }

  /**
   * Updates intentionally hit and miss counter to adjust with JSR107.
   *
   * @see org.cache2k.Cache#replace(Object, Object)
   */
  public Semantic<K, V, Boolean> replace(final K key, final V value) {
    return new Semantic.UpdateExisting<K, V, Boolean>() {

      @Override
      public void update(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          c.result(true);
          c.put(value);
          return;
        }
        c.result(false);
      }

    };
  }

  public Semantic<K, V, Boolean> replace(final K key, final V value, final V newValue) {
    return new Semantic.UpdateExisting<K, V, Boolean>() {

      @Override
      public void update(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss() &&
          ( (value == null && e.getValueOrException() == null) ||
            value.equals(e.getValueOrException())) ) {
          c.result(true);
          c.put(newValue);
          return;
        }
        c.result(false);
      }

    };
  }

  public Semantic<K, V, CacheEntry<K,V>> replaceOrGet(final K key, final V value,
                                                      final V newValue, final CacheEntry<K, V> dummyEntry) {
    return new Semantic.UpdateExisting<K, V, CacheEntry<K,V>>() {

      @Override
      public void update(Progress<K, V, CacheEntry<K, V>> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss()) {
          if (e.getValueOrException().equals(value)) {
            c.result(null);
            c.put(newValue);
            return;
          }
          c.entryResult(e);
          return;
        }
        c.result(dummyEntry);
      }

    };
  }

  public Semantic<K, V, Boolean> remove(final K key, final V value) {
    return new Semantic.UpdateExisting<K, V, Boolean>() {

      @Override
      public void update(Progress<K, V, Boolean> c, ExaminationEntry<K, V> e) {
        if (c.isPresentOrMiss() &&
          ( (value == null && e.getValueOrException() == null) ||
             value.equals(e.getValueOrException())) ) {
          c.result(true);
          c.remove();
          return;
        }
        c.result(false);
      }

    };
  }

  static class MutableEntryOnProgress<K, V> implements MutableCacheEntry<K, V> {

    ExaminationEntry<K, V> entry;
    Progress<K, V, ?> progress;
    boolean originalExists = false;
    boolean mutate = false;
    boolean remove = false;
    boolean exists = false;
    V value = null;
    boolean readThrough = false;
    boolean customExpiry = false;
    long expiry;

    /**
     * Sets exists and value together since it must be guaranteed that getValue() returns
     * a value after exists yields true. It is critical that the isPresentOrMiss() check is
     * only done once, since it depends on the current time.
     */
    public MutableEntryOnProgress(final Progress<K, V, ?> _progress, final ExaminationEntry<K, V> _entry, boolean _readThrough) {
      readThrough = _readThrough;
      entry = _entry;
      progress = _progress;
      if (_progress.isPresentOrMiss()) {
        value = entry.getValueOrException();
        originalExists = exists = true;
      }
    }

    @Override
    public boolean exists() {
      return exists;
    }

    @Override
    public void setValue(final V v) {
      mutate = true;
      exists = true;
      remove = false;
      value = v;
    }

    @Override
    public void setException(final Throwable ex) {
      mutate = true;
      exists = true;
      remove = false;
      value = (V) new ExceptionWrapper(ex);
    }

    @Override
    public void setExpiry(final long t) {
      customExpiry = true;
      expiry = t;
    }

    @Override
    public void remove() {
      if (mutate && !originalExists) {
        mutate = false;
      } else {
        mutate = remove = true;
      }
      exists = false;
      value = null;
    }

    @Override
    public K getKey() {
      return entry.getKey();
    }

    @Override
    public V getValue() {
      if (!exists && !mutate && readThrough) {
        throw new NeedsLoadRestartException();
      }
      if (value instanceof ExceptionWrapper) {
        throw progress.propagateException(entry.getKey(), (ExceptionWrapper) value);
      }
      return value;
    }

    @Override
    public Throwable getException() {
      if (!exists && !mutate && readThrough) {
        throw new NeedsLoadRestartException();
      }
      if (value instanceof ExceptionWrapper) {
        return ((ExceptionWrapper) value).getException();
      }
      return null;
    }

    @Override
    public long getLastModification() {
      return entry.getLastModification();
    }

    public void sendMutationCommandIfNeeded() {
      if (mutate) {
        if (remove) {
          progress.remove();
          return;
        }
        if (customExpiry) {
          progress.putAndSetExpiry(value, expiry);
          return;
        }
        progress.put(value);
        return;
      }
      if (customExpiry) {
        progress.expire(expiry);
      }
    }
  }

  public <R> Semantic<K, V, R> invoke(final K key, final boolean _readThrough, final EntryProcessor<K, V, R> _processor) {
    return new Semantic.UpdateExisting<K, V, R>() {
      @Override
      public void update(final Progress<K, V, R> c, final ExaminationEntry<K, V> e) {
        MutableEntryOnProgress<K, V> _mutableEntryOnProgress = new MutableEntryOnProgress<K, V>(c, e, _readThrough);
        try {
          R _result = _processor.process(_mutableEntryOnProgress);
          c.result(_result);
        } catch (NeedsLoadRestartException rs) {
          c.loadAndMutation();
          return;
        } catch (Throwable t) {
          c.failure(new EntryProcessingException(t));
          return;
        }
        _mutableEntryOnProgress.sendMutationCommandIfNeeded();
      }

      /** No operation, result is set by the entry processor */
      @Override
      public void loaded(final Progress<K, V, R> c, final ExaminationEntry<K, V> e) { }
    };
  }

  public Semantic<K, V, Void> expire(K key, final long t) {
    return new Semantic.UpdateExisting<K, V, Void>() {
      @Override
      public void update(Progress c, ExaminationEntry e) {
        if (t == ExpiryTimeValues.NO_CACHE ||
            t == ExpiryTimeValues.REFRESH) {
          if (c.isPresentOrInRefreshProbation()) {
            c.expire(t);
          }
          return;
        }
        if (c.isPresent()) {
          c.expire(t);
        }
      }
    };
  }

  public static class NeedsLoadRestartException extends RestartException { }

}
