package xyz.nifeather.morph.backends.server.renderer;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xiamomc.pluginbase.Annotations.Initializer;
import xiamomc.pluginbase.Exceptions.NullDependencyException;
import xyz.nifeather.morph.MorphPluginObject;
import xyz.nifeather.morph.backends.server.renderer.network.DisplayParameters;
import xyz.nifeather.morph.backends.server.renderer.network.ProtocolHandler;
import xyz.nifeather.morph.backends.server.renderer.network.datawatcher.watchers.SingleWatcher;
import xyz.nifeather.morph.backends.server.renderer.network.datawatcher.watchers.types.LivingEntityWatcher;
import xyz.nifeather.morph.backends.server.renderer.network.datawatcher.watchers.types.PlayerWatcher;
import xyz.nifeather.morph.backends.server.renderer.network.registries.CustomEntries;
import xyz.nifeather.morph.backends.server.renderer.network.registries.RegisterParameters;
import xyz.nifeather.morph.backends.server.renderer.network.registries.RenderRegistry;
import xyz.nifeather.morph.backends.server.renderer.utilties.WatcherUtils;
import xyz.nifeather.morph.config.MorphConfigManager;
import xyz.nifeather.morph.misc.BuildFailedException;
import xyz.nifeather.morph.misc.ExecutionErrorException;

import java.util.Collections;
import java.util.List;

public class ServerRenderer extends MorphPluginObject implements Listener
{
    private final ProtocolHandler protocolHandler;

    public final RenderRegistry registry = new RenderRegistry();

    public ServerRenderer()
    {
        dependencies.cache(registry);
        dependencies.cache(protocolHandler = new ProtocolHandler());

        registry.onUnRegister(this, parameters ->
        {
            var player = parameters.player();
            if (player == null)
                return;

            this.unDisguiseForPlayer(player, parameters.watcher(), WatcherUtils.getAffectedPlayers(player));
        });
    }

    @Initializer
    private void load(MorphConfigManager config)
    {
        // 当前插件中有在禁用过程使用LivingEntityWatcher的处理
        // 因此在这里加上插件是否启用的检查
        if (plugin.isEnabled())
            Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private final List<LivingEntityWatcher> livingEntityWatchers = Collections.synchronizedList(new ObjectArrayList<>());

    @EventHandler
    public void onPlayerStartUsingItem(PlayerInteractEvent event)
    {
        for (var watcher : livingEntityWatchers)
            watcher.onPlayerStartUsingItem(event);
    }

    /**
     * 向后端渲染器注册玩家
     * @param player 目标玩家
     * @param entityType 目标类型
     * @param name 伪装名称
     * @throws ExecutionErrorException If there's an error while registering the player
     */
    @NotNull
    public SingleWatcher registerEntity(Player player, EntityType entityType, String name) throws ExecutionErrorException
    {
        try
        {
            return registry.register(player, new RegisterParameters(entityType, name), w ->
            {
                if (w instanceof LivingEntityWatcher livingEntityWatcher)
                    livingEntityWatchers.add(livingEntityWatcher);
            });
        }
        catch (Exception e)
        {
            unRegisterEntity(player);

            throw ExecutionErrorException.forMethod("registerEntity")
                    .causedBy(e)
                    .withMessage("Can't register player")
                    .create();
        }
    }

    public void unRegisterEntity(Player player)
    {
        try
        {
            var watcher = registry.unregister(player.getUniqueId());

            if (watcher != null)
                this.livingEntityWatchers.remove(watcher);
        }
        catch (Throwable t)
        {
            logger.error("Can't unregister player", t);
        }
    }

    public void refreshStateForPlayer(@NotNull Player player, List<Player> affectedPlayers)
            throws BuildFailedException, NullDependencyException
    {
        var watcher = registry.getWatcher(player.getUniqueId());
        if (watcher == null)
            throw new NullDependencyException("Null Watcher for a existing player?!");

        refreshStateForPlayer(player,
                new DisplayParameters(watcher),
                affectedPlayers);
    }

    /**
     * 刷新玩家的伪装
     * @param player 目标玩家
     * @param displayParameters 和伪装对应的 {@link DisplayParameters}
     */
    public void refreshStateForPlayer(@NotNull Player player, @NotNull DisplayParameters displayParameters, List<Player> affectedPlayers)
            throws BuildFailedException
    {
        if (affectedPlayers.isEmpty()) return;

        var watcher = displayParameters.getWatcher();
        var protocolManager = PacketEvents.getAPI().getPlayerManager();
        var spawnPackets = watcher.buildSpawnPackets();

        affectedPlayers.forEach(p ->
        {
            spawnPackets.forEach(packet -> protocolManager.sendPacket(p, packet));
        });
    }

    /**
     * 允许对"恢复玩家"数据包的构建失败进行一次延迟重试所等待的Tick数。
     * 主要用于缓解 Geyser/Floodgate（基岩版）玩家因Region线程繁忙导致的构建超时问题。
     */
    private static final long RECOVERY_RETRY_TICKS = 2L;

    /**
     * 撤销给定玩家的伪装表现，并将其恢复原状态展示给旁观玩家
     * <p>
     * <b>关于 Geyser/Floodgate（基岩版）玩家的说明：</b><br>
     * 构建"恢复玩家"数据包（{@code watcher.buildSpawnPackets(false)}）需要跳转到目标玩家
     * 所在Region线程同步执行。基岩版玩家的Region Tick通常会有额外的协议转换开销（皮肤/
     * 表单/方块调色板转换等），因此更容易在等待超时时间内得不到响应，从而抛出
     * {@link BuildFailedException}。
     * <p>
     * 过去的实现在捕获到该异常后会直接 {@code return}，导致旁观玩家永远收不到
     * "移除假身/恢复真实玩家"的数据包，从而在客户端（尤其是Geyser）留下不一致的
     * 实体/Tab列表状态，表现为该问题反馈中的网络协议错误。
     * <p>
     * 现在的实现做了两点改进：
     * <ol>
     *     <li>无论后续恢复包是否构建成功，都会 <b>立即且无条件</b> 发送"移除虚拟实体"的
     *     数据包，因为这部分数据不需要跳线程，可以安全地同步构建。</li>
     *     <li>如果构建"恢复玩家"数据包超时失败，不再直接放弃，而是在目标玩家自己的
     *     Region线程上安排一次延迟重试，尽量确保旁观玩家最终能看到正确状态。</li>
     * </ol>
     *
     * @param player 目标玩家
     * @param disguiseWatcher 目标玩家当前使用的伪装Watcher
     * @param affectedPlayers 需要被通知的旁观玩家列表
     */
    public void unDisguiseForPlayer(@Nullable Player player,
                                    SingleWatcher disguiseWatcher,
                                    List<Player> affectedPlayers)
    {
        if (player == null) return;

        var protocolManager = PacketEvents.getAPI().getPlayerManager();
        PlayerWatcher watcher = new PlayerWatcher(player);
        watcher.markSilent(this);

        watcher.writeEntry(CustomEntries.PROFILE, ((CraftPlayer) player).getProfile());
        watcher.writeEntry(CustomEntries.SPAWN_UUID, player.getUniqueId());
        watcher.writeEntry(CustomEntries.SPAWN_ID, player.getEntityId());
        watcher.writeEntry(CustomEntries.PROFILE_LISTED, true);
        watcher.writeEntry(CustomEntries.DONT_INCLUDE_PACKET_IDENTIFIER, true);

        List<PacketWrapper<?>> disposalPackets = Collections.emptyList();
        try
        {
            disposalPackets = disguiseWatcher.buildVirtualEntityDisposalPackets();
        }
        catch (BuildFailedException e)
        {
            logger.error("Can't dispose virtual entity gracefully, BuildFailedException has been thrown!", e);
        }

        // Send the disposal packet(s) immediately and unconditionally. This doesn't
        // require hopping to the target player's region thread, and guarantees that
        // nearby clients (including Geyser/Bedrock ones) get the fake entity/tab entry
        // removed even if the full "restore real player" rebuild below fails or times out.
        for (Player p : affectedPlayers)
        {
            for (PacketWrapper<?> removePacket : disposalPackets)
                protocolManager.sendPacket(p, removePacket);
        }

        List<PacketWrapper<?>> playerSpawnPackets;
        try
        {
            playerSpawnPackets = watcher.buildSpawnPackets(false);
        }
        catch (BuildFailedException e)
        {
            logger.error("Can't build recover packets for player, BuildFailedException has been thrown! " +
                    "Scheduling a retry on the player's own region thread...", e);

            scheduleRecoveryRetry(player, watcher, protocolManager, affectedPlayers);
            return;
        }

        watcher.dispose();

        for (Player p : affectedPlayers)
        {
            for (var packet : playerSpawnPackets)
                protocolManager.sendPacket(p, packet);
        }
    }

    /**
     * 在目标玩家自身的Region调度器上安排一次延迟重试，尝试重新构建并发送
     * "恢复玩家"数据包。用于缓解 {@link #unDisguiseForPlayer} 中首次构建失败
     * （常见于基岩版/Geyser玩家）的情况，避免旁观玩家永久停留在不一致状态。
     */
    private void scheduleRecoveryRetry(@NotNull Player player,
                                       @NotNull PlayerWatcher watcher,
                                       @NotNull PlayerManager protocolManager,
                                       @NotNull List<Player> affectedPlayers)
    {
        if (!player.isOnline())
        {
            watcher.dispose();
            return;
        }

        player.getScheduler().runDelayed(plugin, task ->
        {
            try
            {
                var retryPackets = watcher.buildSpawnPackets(false);

                for (Player p : affectedPlayers)
                {
                    if (!p.isOnline())
                        continue;

                    for (var packet : retryPackets)
                        protocolManager.sendPacket(p, packet);
                }
            }
            catch (BuildFailedException ex)
            {
                logger.error(("Retry also failed while building recover packets for player '%s'! " +
                        "Nearby players may show a desynced state for this player until they relog.")
                        .formatted(player.getName()), ex);
            }
            finally
            {
                watcher.dispose();
            }
        }, () -> watcher.dispose(), RECOVERY_RETRY_TICKS);
    }

    public void dispose()
    {
        registry.reset();
        protocolHandler.dispose();

        PlayerInteractEvent.getHandlerList().unregister(this);
    }
}
