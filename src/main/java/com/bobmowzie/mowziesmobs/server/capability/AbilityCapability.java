package com.bobmowzie.mowziesmobs.server.capability;

import com.bobmowzie.mowziesmobs.server.ability.Ability;
import com.bobmowzie.mowziesmobs.server.ability.AbilityHandler;
import com.bobmowzie.mowziesmobs.server.ability.AbilityType;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.util.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

public class AbilityCapability {

    public interface IAbilityCapability {

        void activateAbility(LivingEntity entity, AbilityType<?> ability);

        void instanceAbilities(LivingEntity entity);

        void tick(LivingEntity entity);

        AbilityType<?>[] getAbilityTypesOnEntity(LivingEntity entity);

        Map<AbilityType<?>, Ability> getAbilityMap();

        Collection<Ability> getAbilities();

        Ability getActiveAbility();

        void setActiveAbility(Ability activeAbility);

        boolean attackingPrevented();

        boolean blockBreakingBuildingPrevented();

        boolean interactingPrevented();

        INBT writeNBT();

        void readNBT(INBT nbt);
    }

    public static class AbilityCapabilityImp implements IAbilityCapability {
        SortedMap<AbilityType<?>, Ability> abilityInstances = new TreeMap<>();
        Ability activeAbility = null;
        Map<String, INBT> nbtMap = new HashMap<>();

        @Override
        public void instanceAbilities(LivingEntity entity) {
            for (AbilityType<?> abilityType : getAbilityTypesOnEntity(entity)) {
                Ability ability = abilityType.makeInstance(entity);
                abilityInstances.put(abilityType, ability);
                if (nbtMap.containsKey(abilityType.getName())) ability.readNBT(nbtMap.get(abilityType.getName()));
            }
        }

        @Override
        public void activateAbility(LivingEntity entity, AbilityType<?> abilityType) {
            Ability ability = abilityInstances.get(abilityType);
            if (ability != null) {
                boolean tryResult = ability.tryAbility();
                if (tryResult) ability.start();
            }
            else System.out.println("Ability " + abilityType.toString() + " does not exist on mob " + entity.getClass().getSimpleName());
        }

        @Override
        public void tick(LivingEntity entity) {
            for (Ability ability : abilityInstances.values()) {
                ability.tick();
            }
        }

        @Override
        public AbilityType<?>[] getAbilityTypesOnEntity(LivingEntity entity) {
            if (entity instanceof PlayerEntity) {
                return AbilityHandler.PLAYER_ABILITIES;
            }
            return new AbilityType[0];
        }

        @Override
        public Map<AbilityType<?>, Ability> getAbilityMap() {
            return abilityInstances;
        }

        @Override
        public Ability getActiveAbility() {
            return activeAbility;
        }

        @Override
        public void setActiveAbility(Ability activeAbility) {
            if (getActiveAbility() != null && getActiveAbility().isUsing()) getActiveAbility().end();
            this.activeAbility = activeAbility;
        }

        @Override
        public Collection<Ability> getAbilities() {
            return abilityInstances.values();
        }

        @Override
        public boolean attackingPrevented() {
            return getActiveAbility() != null && getActiveAbility().preventsAttacking();
        }

        @Override
        public boolean blockBreakingBuildingPrevented() {
            return getActiveAbility() != null && getActiveAbility().preventsBlockBreakingBuilding();
        }

        @Override
        public boolean interactingPrevented() {
            return getActiveAbility() != null && getActiveAbility().preventsInteracting();
        }

        @Override
        public INBT writeNBT() {
            CompoundNBT compound = new CompoundNBT();
            for (Map.Entry<AbilityType<?>, Ability> abilityEntry : getAbilityMap().entrySet()) {
                CompoundNBT nbt = abilityEntry.getValue().writeNBT();
                if (!nbt.isEmpty()) {
                    compound.put(abilityEntry.getKey().getName(), nbt);
                }
            }
            return compound;
        }

        @Override
        public void readNBT(INBT nbt) {
            CompoundNBT compound = (CompoundNBT) nbt;
            Set<String> keys = compound.keySet();
            for (String abilityName : keys) {
                nbtMap.put(abilityName, compound.get(abilityName));
            }
        }
    }

    public static class AbilityStorage implements Capability.IStorage<IAbilityCapability> {
        @Override
        public INBT writeNBT(Capability<AbilityCapability.IAbilityCapability> capability, AbilityCapability.IAbilityCapability instance, Direction side) {
            return instance.writeNBT();
        }

        @Override
        public void readNBT(Capability<AbilityCapability.IAbilityCapability> capability, AbilityCapability.IAbilityCapability instance, Direction side, INBT nbt) {
            instance.readNBT(nbt);
        }
    }

    public static class AbilityProvider implements ICapabilitySerializable<INBT>
    {
        @CapabilityInject(IAbilityCapability.class)
        public static final Capability<IAbilityCapability> ABILITY_CAPABILITY = null;

        private final LazyOptional<IAbilityCapability> instance = LazyOptional.of(ABILITY_CAPABILITY::getDefaultInstance);

        @Override
        public INBT serializeNBT() {
            return ABILITY_CAPABILITY.getStorage().writeNBT(ABILITY_CAPABILITY, this.instance.orElseThrow(() -> new IllegalArgumentException("Lazy optional must not be empty")), null);
        }

        @Override
        public void deserializeNBT(INBT nbt) {
            ABILITY_CAPABILITY.getStorage().readNBT(ABILITY_CAPABILITY, this.instance.orElseThrow(() -> new IllegalArgumentException("Lazy optional must not be empty")), null, nbt);
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
            return cap == ABILITY_CAPABILITY ? instance.cast() : LazyOptional.empty();
        }
    }

    public static final class AbilityEventHandler {
        @SubscribeEvent
        public void onPlayerInteract(PlayerInteractEvent.RightClickEmpty event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onRightClickEmpty(event);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onRightClickBlock(event);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerRightClickItem(PlayerInteractEvent.RightClickItem event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onRightClickWithItem(event);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerRightClickEntity(PlayerInteractEvent.EntityInteract event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onRightClickEntity(event);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onLeftClickEmpty(event);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onLeftClickBlock(event);
                }
            }
        }

        @SubscribeEvent
        public void onLeftClickEntity(AttackEntityEvent event) {
            PlayerEntity player = event.getPlayer();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onLeftClickEntity(event);
                }
            }
        }

        @SubscribeEvent
        public void onTakeDamage(LivingHurtEvent event) {
            LivingEntity player = event.getEntityLiving();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onTakeDamage(event);
                }
            }
        }

        @SubscribeEvent
        public void onJump(LivingEvent.LivingJumpEvent event) {
            LivingEntity player = event.getEntityLiving();
            IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
            if (abilityCapability != null) {
                for (Ability ability : abilityCapability.getAbilities()) {
                    ability.onJump(event);
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public enum AbilityClientEventHandler {
        INSTANCE;

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            PlayerEntity player = Minecraft.getInstance().player;
            if (player != null) {
                IAbilityCapability abilityCapability = AbilityHandler.INSTANCE.getAbilityCapability(player);
                if (abilityCapability != null) {
                    for (Ability ability : abilityCapability.getAbilities()) {
                        ability.onRenderTick(event);
                    }
                }
            }
        }
    }
}
