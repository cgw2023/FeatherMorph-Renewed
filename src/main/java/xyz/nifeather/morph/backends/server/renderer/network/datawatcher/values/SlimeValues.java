package xyz.nifeather.morph.backends.server.renderer.network.datawatcher.values;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;

public class SlimeValues extends MobValues
{
    /**
     * Since 26.2, {@code Slime}/{@code MagmaCube} extend {@code AbstractCubeMob extends AgeableMob},
     * which adds {@code IS_BABY} (16) and {@code AGE_LOCKED} (17) before the size field.
     */
    private static final int SIZE_INDEX = hasAbstractCubeMob() ? 18 : 16;

    public final SingleValue<Integer> SIZE = createSingleAt("slimemagma_size", SIZE_INDEX, 1, EntityDataTypes.INT);

    public SlimeValues()
    {
        super();

        registerSingle(SIZE);
    }

    private static boolean hasAbstractCubeMob()
    {
        try
        {
            Class.forName("net.minecraft.world.entity.monster.cubemob.AbstractCubeMob", false,
                    SlimeValues.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
}
