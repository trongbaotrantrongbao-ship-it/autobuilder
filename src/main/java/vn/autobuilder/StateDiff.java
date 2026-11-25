package vn.autobuilder;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** So sánh block thật với block ảo của Litematica và quyết định cần làm gì. */
public final class StateDiff {

    public enum Act {
        DONE,          // đã khớp
        PLACE,         // cần đặt block
        PLACE_SLAB,    // slab đôi: cần đặt thêm nửa còn lại
        ADJUST,        // chỉnh bằng chuột phải (repeater, comparator, note block, trapdoor, fence gate)
        WATERLOG,      // cần đổ nước vào block
        BLOCKED,       // có block sai chỗ / trạng thái không sửa được -> bỏ qua
        SKIP           // không xử lý (không khí, chất lỏng, nửa trên của block 2 ô...)
    }

    /** Thuộc tính do hệ thống/redstone quyết định, không cần khớp khi đặt. */
    private static final Set<String> IGNORED = Set.of(
            "waterlogged", "powered", "lit", "distance", "persistent",
            "triggered", "power", "stage", "enabled", "bites", "age");

    /** Thuộc tính chỉnh được bằng chuột phải. */
    private static final Set<String> ADJUSTABLE = Set.of("delay", "mode", "note", "open");

    public static boolean isIgnored(Property<?> p, Block b) {
        if (IGNORED.contains(p.getName())) return true;
        return p.getName().equals("open") && b instanceof DoorBlock; // cửa 2 ô: bỏ qua trạng thái mở
    }

    public static List<Property<?>> diffProps(BlockState a, BlockState b) {
        List<Property<?>> out = new ArrayList<>();
        for (Property<?> p : b.getProperties()) {
            if (isIgnored(p, b.getBlock())) continue;
            if (!a.contains(p)) { out.add(p); continue; }
            Object x = a.get(p);
            Object y = b.get(p);
            if (!x.equals(y)) out.add(p);
        }
        return out;
    }

    public static boolean isDependentHalf(BlockState s) {
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
            return true;
        return s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD;
    }

    public static Act diff(BlockState world, BlockState schem) {
        if (schem.isAir() || schem.getBlock() instanceof FluidBlock) return Act.SKIP;
        if (isDependentHalf(schem) && world.getBlock() != schem.getBlock()) return Act.SKIP;

        if (world.getBlock() == schem.getBlock()) {
            if (schem.getBlock() instanceof SlabBlock
                    && schem.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                    && world.get(Properties.SLAB_TYPE) != SlabType.DOUBLE) {
                return Act.PLACE_SLAB;
            }
            List<Property<?>> d = diffProps(world, schem);
            if (d.isEmpty()) {
                if (schem.contains(Properties.WATERLOGGED) && schem.get(Properties.WATERLOGGED)
                        && world.contains(Properties.WATERLOGGED) && !world.get(Properties.WATERLOGGED)) {
                    return Act.WATERLOG;
                }
                return Act.DONE;
            }
            boolean adjustable = true;
            for (Property<?> p : d) {
                if (!ADJUSTABLE.contains(p.getName())) { adjustable = false; break; }
                if (p.getName().equals("open")
                        && !(schem.getBlock() instanceof TrapdoorBlock || schem.getBlock() instanceof FenceGateBlock)) {
                    adjustable = false; break;
                }
            }
            return adjustable ? Act.ADJUST : Act.BLOCKED;
        }
        if (world.isReplaceable()) return Act.PLACE;
        return Act.BLOCKED;
    }

    private StateDiff() {}
}
