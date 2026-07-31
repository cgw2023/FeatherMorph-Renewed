package xyz.nifeather.morph.utilities;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import xyz.nifeather.morph.FeatherMorphMain;
import xyz.nifeather.morph.misc.EntityRetiredException;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class FoliaThreadUtils
{
    // NOTE: 150ms was found to be too tight for Geyser/Floodgate (Bedrock) players,
    // whose region tick does extra protocol-translation work (skin/form/block-palette
    // translation etc). This regularly caused BuildFailedException ("Waiting too long
    // for server thread ... to respond!") for those players, which in turn left nearby
    // players with desynced fake entities/tab entries (visible as protocol errors on
    // the Bedrock/Geyser side). Bumped the default and added a retrying variant below.
    public static Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMillis(500);

    // Default amount of retry attempts for runOnEntitySyncWithRetry(...)
    public static int DEFAULT_MAX_RETRY_ATTEMPTS = 3;

    // Delay (in millis) between retry attempts. Kept small since we're already
    // inside a blocking wait context (e.g. netty thread) and shouldn't stall too long.
    public static long DEFAULT_RETRY_BACKOFF_MILLIS = 25L;

    public static <X> X runOnRegionSync(Location location, Supplier<X> supplier, int timeout)
            throws CancellationException, ExecutionException, TimeoutException, InterruptedException
    {
        var future = delegateRegion(location, supplier);

        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    public static <X> CompletableFuture<X> delegateRegion(Location location, Supplier<X> supplier)
    {
        var nmsWorld = ((CraftWorld) location.getWorld()).getHandle();

        if (TickThread.isTickThreadFor(nmsWorld, location.x(), location.z()))
            return CompletableFuture.completedFuture(supplier.get());

        CompletableFuture<X> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(FeatherMorphMain.getInstance(), location, task -> future.complete(supplier.get()));

        return future;
    }

    public static <X, E extends Entity> X runOnEntitySync(E bukkitEntity, Function<E, X> func, Duration timeout)
            throws CancellationException, ExecutionException, TimeoutException, InterruptedException
    {
        return delegateEntity(bukkitEntity, func).get(timeout.getNano(), TimeUnit.NANOSECONDS);
    }

    /**
     * Same as {@link #runOnEntitySync(Entity, Function, Duration)}, but retries a few times
     * with a small backoff instead of failing immediately on a single timeout.
     * <p>
     * This smooths out transient lag spikes on the target entity's region thread, which are
     * especially common for Geyser/Floodgate (Bedrock) players whose region tick does extra
     * protocol-translation work on top of normal entity/player ticking.
     *
     * @param bukkitEntity The target entity
     * @param func         The function to run on the entity's owning thread
     * @param timeout      The timeout for EACH attempt
     * @param maxAttempts  The maximum amount of attempts before giving up and throwing TimeoutException
     */
    public static <X, E extends Entity> X runOnEntitySyncWithRetry(E bukkitEntity, Function<E, X> func,
                                                                   Duration timeout, int maxAttempts)
            throws CancellationException, ExecutionException, TimeoutException, InterruptedException
    {
        TimeoutException lastTimeout = null;

        for (int attempt = 0; attempt < Math.max(1, maxAttempts); attempt++)
        {
            try
            {
                return runOnEntitySync(bukkitEntity, func, timeout);
            }
            catch (TimeoutException e)
            {
                lastTimeout = e;

                // Give the busy region thread (Geyser translation work, chunk gen, etc.)
                // a short window to catch up before trying again.
                if (attempt < maxAttempts - 1)
                    Thread.sleep(DEFAULT_RETRY_BACKOFF_MILLIS);
            }
        }

        throw lastTimeout;
    }

    /**
     * Overload of {@link #runOnEntitySyncWithRetry(Entity, Function, Duration, int)} using
     * {@link #DEFAULT_WAIT_TIMEOUT} and {@link #DEFAULT_MAX_RETRY_ATTEMPTS}.
     */
    public static <X, E extends Entity> X runOnEntitySyncWithRetry(E bukkitEntity, Function<E, X> func)
            throws CancellationException, ExecutionException, TimeoutException, InterruptedException
    {
        return runOnEntitySyncWithRetry(bukkitEntity, func, DEFAULT_WAIT_TIMEOUT, DEFAULT_MAX_RETRY_ATTEMPTS);
    }

    /**
     * Returns a {@link CompletableFuture} that will be complete on the Entity's thread,
     *   as an alternative for Entity#getScheduler()
     *
     * @return A {@link CompletableFuture} that provides the state,
     * @exception EntityRetiredException The entity has died (retired)
     * @apiNote <b>In case of the entity region died on folia, you may want to set a maximum wait time!</b>
     */
    public static <X, E extends Entity> CompletableFuture<X> delegateEntity(E bukkitEntity, Function<E, X> func)
    {
        if (isTickThreadFor(bukkitEntity))
            return CompletableFuture.completedFuture(func.apply(bukkitEntity));

        CompletableFuture<X> future = new CompletableFuture<>();

        bukkitEntity.getScheduler().run(FeatherMorphMain.getInstance(),
                task -> future.complete(func.apply(bukkitEntity)),
                () -> future.completeExceptionally(new EntityRetiredException())); //retired: entity removed

        return future;
    }

    public static void runAtLocationSync(Location location, Consumer<World> worldConsumer, Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException
    {
        delegateLocation(location).thenAccept(worldConsumer).get(timeout.getNano(), TimeUnit.NANOSECONDS);
    }

    public static CompletableFuture<World> delegateLocation(Location location)
    {
        var world = location.getWorld();
        var nmsWorld = ((CraftWorld)location.getWorld()).getHandle();

        // Make sure we call the method that uses block location
        if (TickThread.isTickThreadFor(nmsWorld, 0d + location.getBlockX(), 0d + location.getBlockZ()))
            return CompletableFuture.completedFuture(world);

        CompletableFuture<World> future = new CompletableFuture<>();

        Bukkit.getRegionScheduler().run(FeatherMorphMain.getInstance(),
                location,
                task -> future.complete(world));

        return future;
    }

    public static boolean isTickThreadFor(@Nullable Entity bukkitEntity)
    {
        return bukkitEntity != null && Bukkit.isOwnedByCurrentRegion(bukkitEntity);
    }

    public static boolean isTickThreadFor(Location location)
    {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    public static boolean notInSameRegion(Location baseLocation, Location targetLocation)
    {
        return Bukkit.isOwnedByCurrentRegion(baseLocation) == Bukkit.isOwnedByCurrentRegion(targetLocation);
    }

    // https://docs.papermc.io/paper/dev/folia-support/#checking-for-folia
    public static boolean isFolia()
    {
        try
        {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
}
