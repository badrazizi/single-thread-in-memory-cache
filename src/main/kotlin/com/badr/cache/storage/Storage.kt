package com.badr.cache.storage

import com.badr.cache.core.Future
import com.badr.cache.core.Promise
import com.badr.cache.extensions.safe
import java.util.Collections.synchronizedCollection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.Throws

class Storage {
    private val evictionPeriodic = Executors.newScheduledThreadPool(1)
    private val ioThread = Executors.newFixedThreadPool(1)

    private val storage = synchronizedCollection(arrayListOf<Data<Any>>())

    private val evictionRunnable: Runnable = Runnable {
        safe {
            ioThread.submit { evict() }
        }
    }

    init {
        evictionPeriodic.scheduleAtFixedRate(evictionRunnable, 0, 1, TimeUnit.SECONDS)
    }

    fun isTerminated(): Boolean = ioThread.isTerminated && evictionPeriodic.isTerminated

    fun isRunning(): Boolean = !ioThread.isTerminated && !evictionPeriodic.isTerminated

    fun shutDown() {
        evictionPeriodic.shutdown()
        ioThread.shutdown()
    }

    @Throws(IllegalStateException::class)
    fun shutDownNow(timeout: Long, unit: TimeUnit): List<Runnable> {
        val l1 = evictionPeriodic.shutdownNow()
        val l2 = ioThread.shutdownNow()

        check(timeout > 0)

        evictionPeriodic.awaitTermination(timeout, unit)
        ioThread.awaitTermination(timeout, unit)

        return l1 + l2
    }

    fun <T : Any> add(key: String, value: T, lifeTime: Long = 0, unit: TimeUnit = TimeUnit.SECONDS): Future<Boolean> {
        val promise = Promise.promise<Boolean>()

        ioThread.submit {

            val iterator = storage.iterator()
            while (iterator.hasNext()) {
                val data = iterator.next()
                if (data.key == key) {
                    data.setValue(value).updateAdded().setLifeTime(lifeTime).setTimeUnit(unit)
                    promise.complete(true)
                    return@submit
                }
            }

            val added = storage.add(
                Data<Any>()
                    .setKey(key)
                    .setValue(value)
                    .setLifeTime(lifeTime)
                    .setTimeUnit(unit)
            )

            if (added) {
                promise.complete(true)
            } else {
                promise.fail("Could not add $key to cache storage")
            }

        }

        return promise.future()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): Future<T> {
        val promise = Promise.promise<T>()

        try {
            ioThread.submit {

                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (data.key == key) {
                        try {
                            val cast = data.value as T
                            data.updateAdded()
                            promise.complete(cast)
                        } catch (e: Exception) {
                            promise.fail(e)
                        }
                        return@submit
                    }
                }

                promise.fail("Could not find any value for $key")
            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(predicate: (T) -> Boolean): Future<T> {
        val promise = Promise.promise<T>()

        try {
            ioThread.submit {

                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    safe {
                        val cast = data.value as T
                        if (predicate(cast)) {
                            data.updateAdded()
                            promise.complete(cast)
                            return@safe
                        }
                    }
                }

                promise.fail("Could not find any value for giving predicate")
            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun get(vararg keys: String): Future<HashMap<String, Any>> {
        val promise = Promise.promise<HashMap<String, Any>>()

        if (keys.isEmpty()) {
            promise.fail("cache storage is empty")
            return promise.future()
        }

        try {
            ioThread.submit {

                val hashMap = hashMapOf<String, Any>()
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (data.key in keys) {
                        data.updateAdded()
                        hashMap[data.key] = data.value
                    }
                }

                promise.complete(hashMap)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun getKeys(vararg regexs: Regex): Future<List<String>> {
        val promise = Promise.promise<List<String>>()

        if (regexs.isEmpty()) {
            promise.complete(emptyList())
            return promise.future()
        }

        try {
            ioThread.submit {

                val keys = mutableListOf<String>()
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    for (regex in regexs) {
                        if (regex.containsMatchIn(data.key)) keys.add(data.key)
                    }
                }

                promise.complete(keys)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun getAllKeys(): Future<List<String>> {
        val promise = Promise.promise<List<String>>()

        try {
            ioThread.submit {

                val keys = mutableListOf<String>()
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    keys.add(data.key)
                }

                promise.complete(keys)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun getKeysCount(): Future<Int> {
        val promise = Promise.promise<Int>()

        try {
            ioThread.submit { promise.complete(storage.size) }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun have(key: String): Future<Boolean> {
        val promise = Promise.promise<Boolean>()

        try {
            ioThread.submit {

                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (data.key.equals(key, true)) {
                        promise.complete(true)
                        return@submit
                    }
                }

                promise.complete(false)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    private fun evict() = safe {
        if (storage.isEmpty()) {
            return@safe
        }

        val iterator = storage.iterator()
        while (iterator.hasNext()) {
            val data = iterator.next()

            if (data.lifeTime <= 0L) {
                continue
            }

            val lifeTime = data.timeUnit.toSeconds(data.lifeTime)
            if ((data.added + lifeTime) <= (System.currentTimeMillis() / 1000)) {
                iterator.remove()
            }
        }
    }

    fun evict(vararg keys: String): Future<Int> {
        val promise = Promise.promise<Int>()

        if (keys.isEmpty()) {
            promise.complete(0)
            return promise.future()
        }

        try {
            ioThread.submit {

                var count = 0
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (data.key in keys) {
                        iterator.remove()
                        count++

                        if (keys.size == 1) {
                            promise.complete(count)
                            return@submit
                        }
                    }
                }

                promise.complete(count)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun evictAllExcept(vararg keys: String): Future<Int> {
        val promise = Promise.promise<Int>()

        if (keys.isEmpty()) {
            promise.complete(0)
            return promise.future()
        }

        try {
            ioThread.submit {

                var count = 0
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (data.key !in keys) {
                        iterator.remove()
                        count++
                    }
                }

                promise.complete(count)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

    fun evict(regex: Regex): Future<Int> {
        val promise = Promise.promise<Int>()

        try {
            ioThread.submit {

                var count = 0
                val iterator = storage.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    if (regex.containsMatchIn(data.key)) {
                        iterator.remove()
                        count++
                    }
                }

                promise.complete(count)

            }
        } catch (e: Exception) {
            promise.fail(e)
        }

        return promise.future()
    }

  companion object {
    private var storage: Storage = Storage()

    fun getDefault(): Storage {
      return storage
    }

    fun newInstance(): Storage = Storage()
  }
}