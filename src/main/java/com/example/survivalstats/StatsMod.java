package com.example.survivalstats;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.init.PotionTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.DamageSource;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = StatsMod.MODID, name = "Survival Stats", version = "1.0", acceptedMinecraftVersions = "[1.12.2]")
public class StatsMod {
    public static final String MODID = "survivalstats";
    public static SimpleNetworkWrapper NET;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        NET = NetworkRegistry.INSTANCE.newSimpleChannel(MODID);
        NET.registerMessage(SyncMessage.Handler.class, SyncMessage.class, 0, Side.CLIENT);
    }

    // ===================== Stats =====================
    public static class Stats {
        public static final int MAX = 20;

        private static NBTTagCompound tag(EntityPlayer p) {
            NBTTagCompound data = p.getEntityData();
            if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
                data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
            }
            return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        }

        private static int get(EntityPlayer p, String key) {
            NBTTagCompound t = tag(p);
            return t.hasKey(key) ? t.getInteger(key) : MAX;
        }

        private static void set(EntityPlayer p, String key, int v) {
            tag(p).setInteger(key, Math.max(0, Math.min(MAX, v)));
        }

        public static int getThirst(EntityPlayer p) { return get(p, "ss_thirst"); }
        public static int getBlood(EntityPlayer p) { return get(p, "ss_blood"); }
        public static void setThirst(EntityPlayer p, int v) { set(p, "ss_thirst", v); }
        public static void setBlood(EntityPlayer p, int v) { set(p, "ss_blood", v); }
        public static int getTimer(EntityPlayer p) { return tag(p).getInteger("ss_timer"); }
        public static void setTimer(EntityPlayer p, int v) { tag(p).setInteger("ss_timer", v); }

        public static void sync(EntityPlayer p) {
            if (p instanceof EntityPlayerMP) {
                NET.sendTo(new SyncMessage(getThirst(p), getBlood(p)), (EntityPlayerMP) p);
            }
        }
    }

    // ===================== Network =====================
    public static class SyncMessage implements IMessage {
        public int thirst, blood;

        public SyncMessage() {}

        public SyncMessage(int thirst, int blood) {
            this.thirst = thirst;
            this.blood = blood;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            thirst = buf.readInt();
            blood = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(thirst);
            buf.writeInt(blood);
        }

        public static class Handler implements IMessageHandler<SyncMessage, IMessage> {
            @Override
            public IMessage onMessage(final SyncMessage m, MessageContext ctx) {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        HudHandler.thirst = m.thirst;
                        HudHandler.blood = m.blood;
                    }
                });
                return null;
            }
        }
    }

    // ===================== Game logic =====================
    @Mod.EventBusSubscriber(modid = MODID)
    public static class StatsHandler {
        static final int THIRST_DRAIN_TICKS = 600;
        static final int THIRST_DRAIN_SPRINT = 300;
        static final int APPLE_BLOOD = 4;
        static final int GOLDEN_APPLE_BLOOD = 10;
        static final int WATER_BOTTLE_THIRST = 6;
        static final int LOW_BLOOD = 6;

        @SubscribeEvent
        public static void onTick(TickEvent.PlayerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || e.player.world.isRemote) return;
            EntityPlayer p = e.player;
            if (p.isCreative() || p.isSpectator()) return;

            int t = (Stats.getTimer(p) + 1) % 1200;
            int thirst = Stats.getThirst(p);
            int blood = Stats.getBlood(p);
            boolean changed = false;

            int drain = p.isSprinting() ? THIRST_DRAIN_SPRINT : THIRST_DRAIN_TICKS;
            if (t % drain == 0 && thirst > 0) {
                thirst--;
                changed = true;
            }
            if (thirst == 0 && t % 100 == 0 && blood > 0) {
                blood--;
                changed = true;
            }
            if (blood <= LOW_BLOOD && t % 40 == 0) {
                p.addPotionEffect(new PotionEffect(MobEffects.SLOWNESS, 80, 0, false, false));
            }
            if (blood == 0 && t % 40 == 0) {
                p.attackEntityFrom(DamageSource.STARVE, 1.0F);
            }

            Stats.setTimer(p, t);
            if (changed) {
                Stats.setThirst(p, thirst);
                Stats.setBlood(p, blood);
            }
            if (changed || t % 20 == 0) Stats.sync(p);
        }

        @SubscribeEvent
        public static void onHurt(LivingHurtEvent e) {
            if (!(e.getEntityLiving() instanceof EntityPlayerMP)) return;
            EntityPlayerMP p = (EntityPlayerMP) e.getEntityLiving();
            if (p.isCreative()) return;
            int loss = (int) Math.ceil(e.getAmount() / 2.0F);
            if (loss <= 0) return;
            Stats.setBlood(p, Stats.getBlood(p) - loss);
            Stats.sync(p);
        }

        @SubscribeEvent
        public static void onUseFinish(LivingEntityUseItemEvent.Finish e) {
            if (!(e.getEntityLiving() instanceof EntityPlayerMP)) return;
            EntityPlayerMP p = (EntityPlayerMP) e.getEntityLiving();
            ItemStack s = e.getItem();
            if (s.getItem() == Items.APPLE) {
                Stats.setBlood(p, Stats.getBlood(p) + APPLE_BLOOD);
            } else if (s.getItem() == Items.GOLDEN_APPLE) {
                Stats.setBlood(p, Stats.getBlood(p) + GOLDEN_APPLE_BLOOD);
            } else if (s.getItem() == Items.POTIONITEM
                    && PotionUtils.getPotionFromItem(s) == PotionTypes.WATER) {
                Stats.setThirst(p, Stats.getThirst(p) + WATER_BOTTLE_THIRST);
            } else {
                return;
            }
            Stats.sync(p);
        }

        @SubscribeEvent
        public static void onRightClick(PlayerInteractEvent.RightClickItem e) {
            EntityPlayer p = e.getEntityPlayer();
            if (p.world.isRemote || p.isCreative()) return;
            ItemStack s = e.getItemStack();
            if (s.getItem() == Items.APPLE
                    && !p.canEat(false)
                    && Stats.getBlood(p) < Stats.MAX
                    && !p.getCooldownTracker().hasCooldown(Items.APPLE)) {
                s.shrink(1);
                p.getCooldownTracker().setCooldown(Items.APPLE, 20);
                Stats.setBlood(p, Stats.getBlood(p) + APPLE_BLOOD);
                Stats.sync(p);
                e.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            Stats.sync(e.player);
        }

        @SubscribeEvent
        public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
            Stats.sync(e.player);
        }

        @SubscribeEvent
        public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
            Stats.setThirst(e.player, Stats.MAX);
            Stats.setBlood(e.player, Stats.MAX);
            Stats.sync(e.player);
        }
    }

    // ===================== HUD (client only) =====================
    @Mod.EventBusSubscriber(value = Side.CLIENT, modid = MODID)
    public static class HudHandler {
        public static int thirst = Stats.MAX;
        public static int blood = Stats.MAX;

        @SubscribeEvent
        public static void onRender(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.gameSettings.hideGUI) return;
            if (mc.player.isCreative() || mc.player.isSpectator()) return;

            ScaledResolution r = e.getResolution();
            int x = r.getScaledWidth() - 106;
            int y = r.getScaledHeight() - 34;

            drawBar(mc, x, y, thirst, 0xFF3C8DFF, "Thirst");
            drawBar(mc, x, y + 14, blood, 0xFFCC1111, "Blood");
        }

        private static void drawBar(Minecraft mc, int x, int y, int value, int color, String label) {
            int w = 100, h = 10;
            Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
            Gui.drawRect(x, y, x + w, y + h, 0xFF333333);
            Gui.drawRect(x, y, x + w * value / Stats.MAX, y + h, color);
            mc.fontRenderer.drawStringWithShadow(label + " " + value + "/" + Stats.MAX, x + 3, y + 1, 0xFFFFFFFF);
        }
    }
      }
