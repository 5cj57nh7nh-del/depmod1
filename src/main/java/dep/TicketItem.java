package dep;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** ПКМ — отправиться в казино-город. Билет тратится. */
public class TicketItem extends Item {
    public TicketItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            if (DepMod.toCasino(sp) && !user.isCreative()) stack.decrement(1);
        }
        return TypedActionResult.success(stack);
    }
}
