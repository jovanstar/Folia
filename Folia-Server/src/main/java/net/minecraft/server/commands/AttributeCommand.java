package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic3CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

public class AttributeCommand {
    private static final DynamicCommandExceptionType ERROR_NOT_LIVING_ENTITY = new DynamicCommandExceptionType(
        name -> Component.translatableEscape("commands.attribute.failed.entity", name)
    );
    private static final Dynamic2CommandExceptionType ERROR_NO_SUCH_ATTRIBUTE = new Dynamic2CommandExceptionType(
        (entityName, attributeName) -> Component.translatableEscape("commands.attribute.failed.no_attribute", entityName, attributeName)
    );
    private static final Dynamic3CommandExceptionType ERROR_NO_SUCH_MODIFIER = new Dynamic3CommandExceptionType(
        (entityName, attributeName, uuid) -> Component.translatableEscape("commands.attribute.failed.no_modifier", attributeName, entityName, uuid)
    );
    private static final Dynamic3CommandExceptionType ERROR_MODIFIER_ALREADY_PRESENT = new Dynamic3CommandExceptionType(
        (entityName, attributeName, uuid) -> Component.translatableEscape("commands.attribute.failed.modifier_already_present", uuid, attributeName, entityName)
    );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(
            Commands.literal("attribute")
                .requires(source -> source.hasPermission(2))
                .then(
                    Commands.argument("target", EntityArgument.entity())
                        .then(
                            Commands.argument("attribute", ResourceArgument.resource(registryAccess, Registries.ATTRIBUTE))
                                .then(
                                    Commands.literal("get")
                                        .executes(
                                            context -> getAttributeValue(
                                                    context.getSource(),
                                                    EntityArgument.getEntity(context, "target"),
                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                    1.0
                                                )
                                        )
                                        .then(
                                            Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                .executes(
                                                    context -> getAttributeValue(
                                                            context.getSource(),
                                                            EntityArgument.getEntity(context, "target"),
                                                            ResourceArgument.getAttribute(context, "attribute"),
                                                            DoubleArgumentType.getDouble(context, "scale")
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("base")
                                        .then(
                                            Commands.literal("set")
                                                .then(
                                                    Commands.argument("value", DoubleArgumentType.doubleArg())
                                                        .executes(
                                                            context -> setAttributeBase(
                                                                    context.getSource(),
                                                                    EntityArgument.getEntity(context, "target"),
                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                    DoubleArgumentType.getDouble(context, "value")
                                                                )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("get")
                                                .executes(
                                                    context -> getAttributeBase(
                                                            context.getSource(),
                                                            EntityArgument.getEntity(context, "target"),
                                                            ResourceArgument.getAttribute(context, "attribute"),
                                                            1.0
                                                        )
                                                )
                                                .then(
                                                    Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                        .executes(
                                                            context -> getAttributeBase(
                                                                    context.getSource(),
                                                                    EntityArgument.getEntity(context, "target"),
                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                    DoubleArgumentType.getDouble(context, "scale")
                                                                )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("reset")
                                                .executes(
                                                    context -> resetAttributeBase(
                                                            context.getSource(),
                                                            EntityArgument.getEntity(context, "target"),
                                                            ResourceArgument.getAttribute(context, "attribute")
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("modifier")
                                        .then(
                                            Commands.literal("add")
                                                .then(
                                                    Commands.argument("id", ResourceLocationArgument.id())
                                                        .then(
                                                            Commands.argument("value", DoubleArgumentType.doubleArg())
                                                                .then(
                                                                    Commands.literal("add_value")
                                                                        .executes(
                                                                            context -> addModifier(
                                                                                    context.getSource(),
                                                                                    EntityArgument.getEntity(context, "target"),
                                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                                    ResourceLocationArgument.getId(context, "id"),
                                                                                    DoubleArgumentType.getDouble(context, "value"),
                                                                                    AttributeModifier.Operation.ADD_VALUE
                                                                                )
                                                                        )
                                                                )
                                                                .then(
                                                                    Commands.literal("add_multiplied_base")
                                                                        .executes(
                                                                            context -> addModifier(
                                                                                    context.getSource(),
                                                                                    EntityArgument.getEntity(context, "target"),
                                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                                    ResourceLocationArgument.getId(context, "id"),
                                                                                    DoubleArgumentType.getDouble(context, "value"),
                                                                                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                                                                                )
                                                                        )
                                                                )
                                                                .then(
                                                                    Commands.literal("add_multiplied_total")
                                                                        .executes(
                                                                            context -> addModifier(
                                                                                    context.getSource(),
                                                                                    EntityArgument.getEntity(context, "target"),
                                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                                    ResourceLocationArgument.getId(context, "id"),
                                                                                    DoubleArgumentType.getDouble(context, "value"),
                                                                                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("remove")
                                                .then(
                                                    Commands.argument("id", ResourceLocationArgument.id())
                                                        .suggests(
                                                            (context, builder) -> SharedSuggestionProvider.suggestResource(
                                                                    getAttributeModifiers(
                                                                        EntityArgument.getEntity(context, "target"),
                                                                        ResourceArgument.getAttribute(context, "attribute")
                                                                    ),
                                                                    builder
                                                                )
                                                        )
                                                        .executes(
                                                            context -> removeModifier(
                                                                    context.getSource(),
                                                                    EntityArgument.getEntity(context, "target"),
                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                    ResourceLocationArgument.getId(context, "id")
                                                                )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("value")
                                                .then(
                                                    Commands.literal("get")
                                                        .then(
                                                            Commands.argument("id", ResourceLocationArgument.id())
                                                                .suggests(
                                                                    (context, builder) -> SharedSuggestionProvider.suggestResource(
                                                                            getAttributeModifiers(
                                                                                EntityArgument.getEntity(context, "target"),
                                                                                ResourceArgument.getAttribute(context, "attribute")
                                                                            ),
                                                                            builder
                                                                        )
                                                                )
                                                                .executes(
                                                                    context -> getAttributeModifier(
                                                                            context.getSource(),
                                                                            EntityArgument.getEntity(context, "target"),
                                                                            ResourceArgument.getAttribute(context, "attribute"),
                                                                            ResourceLocationArgument.getId(context, "id"),
                                                                            1.0
                                                                        )
                                                                )
                                                                .then(
                                                                    Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                                        .executes(
                                                                            context -> getAttributeModifier(
                                                                                    context.getSource(),
                                                                                    EntityArgument.getEntity(context, "target"),
                                                                                    ResourceArgument.getAttribute(context, "attribute"),
                                                                                    ResourceLocationArgument.getId(context, "id"),
                                                                                    DoubleArgumentType.getDouble(context, "scale")
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static AttributeInstance getAttributeInstance(Entity entity, Holder<Attribute> attribute) throws CommandSyntaxException {
        AttributeInstance attributeInstance = getLivingEntity(entity).getAttributes().getInstance(attribute);
        if (attributeInstance == null) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(entity.getName(), getAttributeDescription(attribute));
        } else {
            return attributeInstance;
        }
    }

    private static LivingEntity getLivingEntity(Entity entity) throws CommandSyntaxException {
        if (!(entity instanceof LivingEntity)) {
            throw ERROR_NOT_LIVING_ENTITY.create(entity.getName());
        } else {
            return (LivingEntity)entity;
        }
    }

    private static LivingEntity getEntityWithAttribute(Entity entity, Holder<Attribute> attribute) throws CommandSyntaxException {
        LivingEntity livingEntity = getLivingEntity(entity);
        if (!livingEntity.getAttributes().hasAttribute(attribute)) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(entity.getName(), getAttributeDescription(attribute));
        } else {
            return livingEntity;
        }
    }

    // Folia start - region threading
    private static void sendMessage(CommandSourceStack src, CommandSyntaxException ex) {
        src.sendFailure((Component)ex.getRawMessage());
    }
    // Folia end - region threading

    private static int getAttributeValue(CommandSourceStack source, Entity target, Holder<Attribute> attribute, double multiplier) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(nmsEntity, attribute); // Folia - region threading
        double d = livingEntity.getAttributeValue(attribute);
        source.sendSuccess(() -> Component.translatable("commands.attribute.value.get.success", getAttributeDescription(attribute), nmsEntity.getName(), d), false); // Folia - region threading
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static int getAttributeBase(CommandSourceStack source, Entity target, Holder<Attribute> attribute, double multiplier) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(nmsEntity, attribute); // Folia - region threading
        double d = livingEntity.getAttributeBaseValue(attribute);
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.base_value.get.success", getAttributeDescription(attribute), nmsEntity.getName(), d), false // Folia - region threading
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static int getAttributeModifier(CommandSourceStack source, Entity target, Holder<Attribute> attribute, ResourceLocation id, double multiplier) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(nmsEntity, attribute); // Folia - region threading
        AttributeMap attributeMap = livingEntity.getAttributes();
        if (!attributeMap.hasModifier(attribute, id)) {
            throw ERROR_NO_SUCH_MODIFIER.create(nmsEntity.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        } else {
            double d = attributeMap.getModifierValue(attribute, id);
            source.sendSuccess(
                () -> Component.translatable(
                        "commands.attribute.modifier.value.get.success", Component.translationArg(id), getAttributeDescription(attribute), nmsEntity.getName(), d // Folia - region threading
                    ),
                false
            );
            return; // Folia - region threading
        }
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static Stream<ResourceLocation> getAttributeModifiers(Entity target, Holder<Attribute> attribute) throws CommandSyntaxException {
        AttributeInstance attributeInstance = getAttributeInstance(target, attribute);
        return attributeInstance.getModifiers().stream().map(AttributeModifier::id);
    }

    private static int setAttributeBase(CommandSourceStack source, Entity target, Holder<Attribute> attribute, double value) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        getAttributeInstance(nmsEntity, attribute).setBaseValue(value); // Folia - region threading
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.base_value.set.success", getAttributeDescription(attribute), nmsEntity.getName(), value), false // Folia - region threading
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static int resetAttributeBase(CommandSourceStack source, Entity target, Holder<Attribute> attribute) throws CommandSyntaxException {
        LivingEntity livingEntity = getLivingEntity(target);
        if (!livingEntity.getAttributes().resetBaseValue(attribute)) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(target.getName(), getAttributeDescription(attribute));
        } else {
            double d = livingEntity.getAttributeBaseValue(attribute);
            source.sendSuccess(
                () -> Component.translatable("commands.attribute.base_value.reset.success", getAttributeDescription(attribute), target.getName(), d), false
            );
            return 1;
        }
    }

    private static int addModifier(
        CommandSourceStack source, Entity target, Holder<Attribute> attribute, ResourceLocation id, double value, AttributeModifier.Operation operation
    ) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        AttributeInstance attributeInstance = getAttributeInstance(nmsEntity, attribute); // Folia - region threading
        AttributeModifier attributeModifier = new AttributeModifier(id, value, operation);
        if (attributeInstance.hasModifier(id)) {
            throw ERROR_MODIFIER_ALREADY_PRESENT.create(nmsEntity.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        } else {
            attributeInstance.addPermanentModifier(attributeModifier);
            source.sendSuccess(
                () -> Component.translatable(
                        "commands.attribute.modifier.add.success", Component.translationArg(id), getAttributeDescription(attribute), nmsEntity.getName() // Folia - region threading
                    ),
                false
            );
            return; // Folia - region threading
        }
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static int removeModifier(CommandSourceStack source, Entity target, Holder<Attribute> attribute, ResourceLocation id) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.schedule((Entity nmsEntity) -> {
            try {
                // Folia end - region threading
        AttributeInstance attributeInstance = getAttributeInstance(nmsEntity, attribute); // Folia - region threading
        if (attributeInstance.removeModifier(id)) {
            source.sendSuccess(
                () -> Component.translatable(
                        "commands.attribute.modifier.remove.success", Component.translationArg(id), getAttributeDescription(attribute), nmsEntity.getName() // Folia - region threading
                    ),
                false
            );
            return; // Folia - region threading
        } else {
            throw ERROR_NO_SUCH_MODIFIER.create(nmsEntity.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        }
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        }, null, 1L);
        return 0;
        // Folia end - region threading
    }

    private static Component getAttributeDescription(Holder<Attribute> attribute) {
        return Component.translatable(attribute.value().getDescriptionId());
    }
}
