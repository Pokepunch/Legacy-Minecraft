package wily.legacy.client;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.Files;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Difficulty;
import wily.factoryapi.FactoryAPI;
import wily.factoryapi.FactoryAPIClient;
import wily.factoryapi.base.ArbitrarySupplier;
import wily.factoryapi.base.Bearer;
import wily.factoryapi.base.Stocker;
import wily.factoryapi.base.client.MinecraftAccessor;
import wily.factoryapi.base.network.CommonNetwork;
import wily.legacy.Legacy4J;
import wily.legacy.Legacy4JClient;
import wily.legacy.client.controller.*;
import wily.legacy.client.screen.LegacyConfigWidget;
import wily.legacy.config.LegacyConfig;
import wily.legacy.config.LegacyConfigDisplay;
import wily.legacy.network.PlayerInfoSync;
import wily.legacy.util.LegacyComponents;

import java.io.*;
import java.util.*;
import java.util.function.*;


public class LegacyOptions {
    @Deprecated
    private static final File deprecatedLegacyOptionssFile = FactoryAPI.getConfigDirectory().resolve("legacy_options.txt").toFile();
    @Deprecated
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static final Function<OptionInstance<?>,LegacyConfig<?>> LEGACY_OPTION_OPTION_INSTANCE_CACHE = Util.memoize(LegacyOptions::create);

    public static final Map<Component, Component> vanillaCaptionOverrideMap = new HashMap<>(Map.of(Component.translatable("key.sprint"),Component.translatable("options.key.toggleSprint"),Component.translatable("key.sneak"),Component.translatable("options.key.toggleSneak")));

    
    public static final LegacyConfig.StorageHandler CLIENT_STORAGE = new LegacyConfig.StorageHandler("legacy/client_options.json"){
        @Override
        public void load() {
            for (KeyMapping keyMapping : Minecraft.getInstance().options.keyMappings) {
                LegacyKeyMapping mapping = LegacyKeyMapping.of(keyMapping);
                register(LegacyConfig.create("component_" + keyMapping.getName(), ArbitrarySupplier.empty(),Optional.ofNullable(((LegacyKeyMapping) keyMapping).getDefaultBinding()), Bearer.of(()->Optional.ofNullable(mapping.getBinding()),o->mapping.setBinding(o.map(b-> !b.isBindable ? null : b).orElse(mapping.getDefaultBinding()))), ControllerBinding.OPTIONAL_CODEC, m->{}, this));
            }
            loadDeprecated();
            super.load();
        }
    };
    public static final LegacyConfig.StorageAccess CLIENT_PARSE_WHEN_LOADED_STORAGE_ACCESS = new LegacyConfig.StorageAccess(){
        @Override
        public <T> void whenParsed(LegacyConfig<T> config, T newValue) {
            FactoryAPIClient.SECURE_EXECUTOR.executeNowIfPossible(()->LegacyConfig.StorageAccess.super.whenParsed(config,newValue), MinecraftAccessor.getInstance()::hasGameLoaded);
        }

        @Override
        public void save() {
            CLIENT_STORAGE.save();
        }
    };
    
    public static final LegacyConfig.StorageAccess VANILLA_STORAGE_ACCESS = ()-> Minecraft.getInstance().options.save();


    public static <T> LegacyConfig<T> of(OptionInstance<T> optionInstance) {
        return (LegacyConfig<T>) LEGACY_OPTION_OPTION_INSTANCE_CACHE.apply(optionInstance);
    }

    public static <T> LegacyConfig<T> create(OptionInstance<T> optionInstance) {
        return LegacyConfig.createWithWidget(LegacyConfig.create(OptionInstanceAccessor.of(optionInstance).getKey(), ArbitrarySupplier.of(new LegacyConfigDisplay<>(vanillaCaptionOverrideMap.getOrDefault(optionInstance.caption,optionInstance.caption))), OptionInstanceAccessor.of(optionInstance).defaultValue(), Bearer.of(optionInstance::get, v->{
            if (optionInstance.values() instanceof OptionInstance.CycleableValueSet<T> set) {
                set.valueSetter().set(optionInstance,v);
            } else optionInstance.set(v);
        }),optionInstance.codec(),v->{}, VANILLA_STORAGE_ACCESS), ()->{
            if (optionInstance.values().equals(OptionInstance.BOOLEAN_VALUES)){
                return (LegacyConfigWidget<T>) LegacyConfigWidget.createTickBox(v-> OptionInstanceAccessor.of(optionInstance).tooltip().apply((T)v));
            } else if (optionInstance.values() instanceof OptionInstance.CycleableValueSet<T> set) {
                return  LegacyConfigWidget.createSliderFromInt(OptionInstanceAccessor.of(optionInstance).tooltip()::apply, (c,v)-> CommonComponents.optionNameValue(c,optionInstance.toString.apply(optionInstance.get())), i->set.valueListSupplier().getSelectedList().get(i), v-> set.valueListSupplier().getSelectedList().indexOf(v), ()->set.valueListSupplier().getSelectedList().size());
            } else if (optionInstance.values() instanceof OptionInstance.SliderableValueSet<T> set) {
                return  LegacyConfigWidget.createSlider(OptionInstanceAccessor.of(optionInstance).tooltip()::apply, (c,v)-> optionInstance.toString.apply(optionInstance.get()), b->set.fromSliderValue(b.getValue()), set::toSliderValue);
            }
            return (x,y,width,afterSet)-> config-> optionInstance.createButton(Minecraft.getInstance().options, x, y, width, afterSet);
        });
    }

    public static LegacyConfig<Boolean> createBoolean(String key, boolean defaultValue) {
        return createBoolean(key, defaultValue, b-> {});
    }

    public static LegacyConfig<Boolean> createBoolean(String key, boolean defaultValue, Consumer<Boolean> consumer) {
        return createBoolean(key, b->null, defaultValue, consumer);
    }
    public static LegacyConfig<Boolean> createBoolean(String key, Function<Boolean,Component> tooltipFunction, boolean defaultValue) {
        return createBoolean(key, tooltipFunction, defaultValue, b->{});
    }
    public static LegacyConfig<Boolean> createBoolean(String key, Function<Boolean,Component> tooltipFunction, boolean defaultValue, Consumer<Boolean> consumer) {
        return LegacyConfig.createBoolean(key, ArbitrarySupplier.of(new LegacyConfigDisplay<>(key, tooltipFunction)), defaultValue, consumer, CLIENT_STORAGE);
    }

    public static LegacyConfig<Integer> createInteger(String key, BiFunction<Component,Integer,Component> captionFunction, int min, IntSupplier max, int defaultValue) {
        return createInteger(key, captionFunction, min, max, defaultValue, v-> {});
    }

    public static LegacyConfig<Integer> createInteger(String key, BiFunction<Component,Integer,Component> captionFunction, int min, IntSupplier max, int defaultValue, Consumer<Integer> consumer) {
        return createInteger(key, v-> null, captionFunction, min, max, defaultValue, consumer);
    }

    public static LegacyConfig<Integer> createInteger(String key, Function<Integer,Component> tooltipFunction, BiFunction<Component,Integer,Component> captionFunction, int min, IntSupplier max, int defaultValue, Consumer<Integer> consumer) {
        return createInteger(key, tooltipFunction, captionFunction, min, max, defaultValue, consumer, CLIENT_STORAGE);
    }

    public static LegacyConfig<Integer> createInteger(String key, Function<Integer,Component> tooltipFunction, BiFunction<Component,Integer,Component> captionFunction, int min, IntSupplier max, int defaultValue, Consumer<Integer> consumer, LegacyConfig.StorageAccess access) {
        return LegacyConfig.createInteger(key, ArbitrarySupplier.of(new LegacyConfigDisplay<>(key, tooltipFunction)), captionFunction, min, max, Integer.MAX_VALUE, defaultValue, consumer, access);
    }

    public static <T> LegacyConfig<T> create(String key, BiFunction<Component,T,Component> captionFunction, Function<Integer,T> valueGetter, Function<T, Integer> valueSetter, Supplier<Integer> valuesSize, T defaultValue, Consumer<T> consumer, LegacyConfig.StorageAccess access) {
        return LegacyConfig.create(key, ArbitrarySupplier.of(new LegacyConfigDisplay<>(key)), captionFunction, valueGetter, valueSetter, valuesSize, defaultValue, consumer, access);
    }

    public static <T> LegacyConfig<T> create(String key, BiFunction<Component,T,Component> captionFunction, Supplier<List<T>> listSupplier, T defaultValue, Consumer<T> consumer) {
        return create(key, v-> null, captionFunction, listSupplier, defaultValue, consumer);
    }

    public static <T> LegacyConfig<T> create(String key, Function<T,Component> tooltipFunction, BiFunction<Component,T,Component> captionFunction, Supplier<List<T>> listSupplier, T defaultValue, Consumer<T> consumer) {
        return LegacyConfig.create(key, ArbitrarySupplier.of(new LegacyConfigDisplay<>(key, tooltipFunction)), captionFunction, i->listSupplier.get().get(i), v-> listSupplier.get().indexOf(v), ()-> listSupplier.get().size(), defaultValue, consumer, CLIENT_STORAGE);
    }


    public static LegacyConfig<Double> createDouble(String key, BiFunction<Component,Double,Component> captionFunction, double defaultValue) {
        return createDouble(key, captionFunction, defaultValue, b->{});
    }
    public static LegacyConfig<Double> createDouble(String key, BiFunction<Component,Double,Component> captionFunction, double defaultValue, Consumer<Double> consumer) {
        return createDouble(key, v-> null, captionFunction, defaultValue, b->{});
    }
    public static LegacyConfig<Double> createDouble(String key, Function<Double,Component> tooltipFunction, BiFunction<Component,Double,Component> captionFunction, double defaultValue) {
        return createDouble(key, tooltipFunction, captionFunction, defaultValue, b->{});
    }

    public static LegacyConfig<Double> createDouble(String key, Function<Double,Component> tooltipFunction, BiFunction<Component,Double,Component> captionFunction, double defaultValue, Consumer<Double> consumer) {
        return LegacyConfig.createDouble(key, ArbitrarySupplier.of(new LegacyConfigDisplay<>(key, tooltipFunction)), captionFunction, defaultValue, consumer, CLIENT_STORAGE);
    }

    public static Component percentValueLabel(Component component, double d) {
        return Component.translatable("options.percent_value", component, (int)(d * 100.0));
    }

    public static <T> Function<T,Component> staticComponent(Component component){
        return v->component;
    }

    public static final LegacyConfig<String> lastLoadedVersion = CLIENT_STORAGE.register(LegacyConfig.create("lastLoadedVersion", ArbitrarySupplier.empty(),"", new Stocker<>(Legacy4J.VERSION.get()), Codec.STRING, v-> {}, CLIENT_STORAGE));

    public static final LegacyConfig<Boolean> animatedCharacter = CLIENT_STORAGE.register(createBoolean("animatedCharacter",true));
    public static final LegacyConfig<Boolean> classicCrafting = CLIENT_STORAGE.register(createBoolean("classicCrafting",false, b-> {
        if (Minecraft.getInstance().player != null) CommonNetwork.sendToServer(new PlayerInfoSync(b ? 1 : 2, Minecraft.getInstance().player));
    }));
    public static final LegacyConfig<Boolean> vanillaTabs = CLIENT_STORAGE.register(createBoolean("vanillaTabs",staticComponent(Component.translatable("legacy.options.vanillaTabs.description")),false));
    public static final LegacyConfig<Boolean> displayLegacyGamma = CLIENT_STORAGE.register(createBoolean("displayGamma", true));
    public static final LegacyConfig<Double> legacyGamma = CLIENT_STORAGE.register(createDouble("gamma", LegacyOptions::percentValueLabel, 0.5));
    public static final LegacyConfig<Boolean> displayHUD = CLIENT_STORAGE.register(createBoolean("displayHUD",true));
    public static final LegacyConfig<Boolean> displayHand = CLIENT_STORAGE.register(createBoolean("displayHand",true));
    public static final LegacyConfig<Boolean> legacyCreativeTab = CLIENT_STORAGE.register(createBoolean("creativeTab", true));
    public static final LegacyConfig<Boolean> searchCreativeTab = CLIENT_STORAGE.register(createBoolean("searchCreativeTab", false));
    public static final LegacyConfig<Integer> autoSaveInterval = CLIENT_STORAGE.register(createInteger("autoSaveInterval",(c,i)-> i == 0 ? Options.genericValueLabel(c,Component.translatable("options.off")) :Component.translatable( "legacy.options.mins_value",c, i * 5),0, ()-> 24,1, i->{/*? if >1.20.1 {*/if (Minecraft.getInstance().hasSingleplayerServer()) Minecraft.getInstance().getSingleplayerServer().onTickRateChanged();/*?}*/}));
    public static final LegacyConfig<Boolean> autoSaveWhenPaused = CLIENT_STORAGE.register(createBoolean("autoSaveWhenPaused",false));
    public static final LegacyConfig<Boolean> inGameTooltips = CLIENT_STORAGE.register(createBoolean("gameTooltips", true));
    public static final LegacyConfig<Boolean> tooltipBoxes = CLIENT_STORAGE.register(createBoolean("tooltipBoxes", true));
    public static final LegacyConfig<Boolean> hints = CLIENT_STORAGE.register(createBoolean("hints", true));
    public static final LegacyConfig<Boolean> flyingViewRolling = CLIENT_STORAGE.register(createBoolean("flyingViewRolling", true));
    public static final LegacyConfig<Boolean> directSaveLoad = CLIENT_STORAGE.register(createBoolean("directSaveLoad", false));
    public static final LegacyConfig<Boolean> vignette = CLIENT_STORAGE.register(createBoolean("vignette", false));
    public static final LegacyConfig<Boolean> minecartSounds = CLIENT_STORAGE.register(createBoolean("minecartSounds", true));
    public static final LegacyConfig<Boolean> caveSounds = CLIENT_STORAGE.register(createBoolean("caveSounds", true));
    public static final LegacyConfig<Boolean> showVanillaRecipeBook = CLIENT_STORAGE.register(createBoolean("showVanillaRecipeBook", false));
    public static final LegacyConfig<Boolean> displayNameTagBorder = CLIENT_STORAGE.register(createBoolean("displayNameTagBorder", true));
    public static final LegacyConfig<Boolean> legacyItemTooltips = CLIENT_STORAGE.register(createBoolean("legacyItemTooltips", true));
    public static final LegacyConfig<Boolean> legacyItemTooltipScaling = CLIENT_STORAGE.register(createBoolean("legacyItemTooltipsScaling", true));
    public static final LegacyConfig<Boolean> invertYController = CLIENT_STORAGE.register(createBoolean("invertYController", false));
    public static final LegacyConfig<Boolean> invertControllerButtons = CLIENT_STORAGE.register(createBoolean("invertControllerButtons", false, (b)-> ControllerBinding.RIGHT_BUTTON.bindingState.block(2)));
    public static final LegacyConfig<Integer> selectedController = CLIENT_STORAGE.register(createInteger("selectedController", (c, i)-> Component.translatable("options.generic_value",c,Component.literal(i+1 + (Legacy4JClient.controllerManager.connectedController == null ? "" : " (%s)".formatted(Legacy4JClient.controllerManager.connectedController.getName())))),  0, ()->15, 0, d -> { if (Legacy4JClient.controllerManager.connectedController!= null) Legacy4JClient.controllerManager.connectedController.disconnect(Legacy4JClient.controllerManager);}));
    public static final LegacyConfig<Controller.Handler> selectedControllerHandler = CLIENT_STORAGE.register(create("selectedControllerHandler", (c, h)-> Component.translatable("options.generic_value",c,h.getName()), ()->((List<Controller.Handler>)ControllerManager.handlers.values()), SDLControllerHandler.getInstance(), d-> {
        ControllerBinding.LEFT_STICK.bindingState.block(2);
        if (Legacy4JClient.controllerManager.connectedController != null) Legacy4JClient.controllerManager.connectedController.disconnect(Legacy4JClient.controllerManager);
    }));
    public static final LegacyConfig<Integer> cursorMode = CLIENT_STORAGE.register(createInteger("cursorMode", (c, i)-> Component.translatable("options.generic_value",c,Component.translatable(i == 0 ? "options.guiScale.auto" : i == 1 ? "team.visibility.always" : "team.visibility.never")), 0, ()->2, 0));
    public static final LegacyConfig<Boolean> unfocusedInputs = CLIENT_STORAGE.register(createBoolean("unfocusedInputs", false));
    public static final LegacyConfig<Double> leftStickDeadZone = CLIENT_STORAGE.register(createDouble("leftStickDeadZone", LegacyOptions::percentValueLabel, 0.25));
    public static final LegacyConfig<Double> rightStickDeadZone = CLIENT_STORAGE.register(createDouble("rightStickDeadZone", LegacyOptions::percentValueLabel, 0.34));
    public static final LegacyConfig<Double> leftTriggerDeadZone = CLIENT_STORAGE.register(createDouble("leftTriggerDeadZone", LegacyOptions::percentValueLabel, 0.2));
    public static final LegacyConfig<Double> rightTriggerDeadZone = CLIENT_STORAGE.register(createDouble("rightTriggerDeadZone", LegacyOptions::percentValueLabel, 0.2));
    public static final LegacyConfig<Integer> hudScale = CLIENT_STORAGE.register(createInteger("hudScale", Options::genericValueLabel, 1, ()->3, 2));
    public static final LegacyConfig<Double> hudOpacity = CLIENT_STORAGE.register(createDouble("hudOpacity", LegacyOptions::percentValueLabel, 0.8));
    public static final LegacyConfig<Double> hudDistance = CLIENT_STORAGE.register(createDouble("hudDistance", LegacyOptions::percentValueLabel, 1.0));
    public static final LegacyConfig<Double> interfaceResolution = CLIENT_STORAGE.register(createDouble("interfaceResolution", (c, d)-> percentValueLabel(c, 0.25 + d * 1.5), 0.5, d -> Minecraft.getInstance().execute(Minecraft.getInstance()::resizeDisplay)));
    public static final LegacyConfig<Double> interfaceSensitivity = CLIENT_STORAGE.register(createDouble("interfaceSensitivity", (c, d)-> percentValueLabel(c, d*2), 0.5, d -> {}));
    public static final LegacyConfig<Double> controllerSensitivity = CLIENT_STORAGE.register(LegacyConfig.createDouble("controllerSensitivity", ArbitrarySupplier.of(new LegacyConfigDisplay<>(Component.translatable("options.sensitivity"))), (c, d)-> percentValueLabel(c, d*2), 0.5, d -> {}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> overrideTerrainFogStart = CLIENT_STORAGE.register(createBoolean("overrideTerrainFogStart", true));
    public static final LegacyConfig<Integer> terrainFogStart = CLIENT_STORAGE.register(createInteger("terrainFogStart", (c,i)-> CommonComponents.optionNameValue(c, Component.translatable("options.chunks", i)), 2, ()-> Minecraft.getInstance().options.renderDistance().get(), 4, d -> {}));
    public static final LegacyConfig<Double> terrainFogEnd = CLIENT_STORAGE.register(createDouble("terrainFogEnd", (c, d) -> percentValueLabel(c, d*2), 0.5));
    public static final LegacyConfig<String> selectedControlType = CLIENT_STORAGE.register(LegacyConfig.create("controlType", ArbitrarySupplier.of(new LegacyConfigDisplay<>("controlType")), (c, i)-> Component.translatable("options.generic_value",c,i.equals("auto") ? Component.translatable("legacy.options.auto_value", ControlType.getActiveType().getDisplayName()) : ControlType.typesMap.get(i).getDisplayName()), Codec.STRING, i-> i == 0 ? "auto" : ControlType.types.get(i - 1).getId().toString(), s1-> s1.equals("auto") ? 0 : (1 + ControlType.types.indexOf(ControlType.typesMap.get(s1))), ()-> ControlType.types.size() + 1, "auto", v-> {}, CLIENT_PARSE_WHEN_LOADED_STORAGE_ACCESS));
    public static final LegacyConfig<Difficulty> createWorldDifficulty = CLIENT_STORAGE.register(LegacyConfig.create("createWorldDifficulty", ArbitrarySupplier.of(new LegacyConfigDisplay<>(Component.translatable("options.difficulty"), Difficulty::getInfo)), (c, d)-> CommonComponents.optionNameValue(c, d.getDisplayName()), Difficulty::byId, Difficulty::getId, ()->Difficulty.values().length, Difficulty.NORMAL, d -> {}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> smoothMovement = CLIENT_STORAGE.register(createBoolean("smoothMovement",true));
    public static final LegacyConfig<Boolean> legacyCreativeBlockPlacing = CLIENT_STORAGE.register(createBoolean("legacyCreativeBlockPlacing",true));
    public static final LegacyConfig<Boolean> smoothAnimatedCharacter = CLIENT_STORAGE.register(createBoolean("smoothAnimatedCharacter",false));
    public static final LegacyConfig<Boolean> autoResolution = CLIENT_STORAGE.register(createBoolean("autoResolution", true, b-> Minecraft.getInstance().execute(()-> Minecraft.getInstance().resizeDisplay())));
    public static final LegacyConfig<Boolean> invertedCrosshair = CLIENT_STORAGE.register(createBoolean("invertedCrosshair",false));
    public static final LegacyConfig<Boolean> legacyDrownedAnimation = CLIENT_STORAGE.register(createBoolean("legacyDrownedAnimation",true));
    public static final LegacyConfig<Boolean> merchantTradingIndicator = CLIENT_STORAGE.register(createBoolean("merchantTradingIndicator",true));
    public static final LegacyConfig<Boolean> itemLightingInHand = CLIENT_STORAGE.register(createBoolean("itemLightingInHand",true));
    public static final LegacyConfig<Boolean> loyaltyLines = CLIENT_STORAGE.register(createBoolean("loyaltyLines",true));
    public static final LegacyConfig<Boolean> controllerToggleCrouch = CLIENT_STORAGE.register(LegacyConfig.createBoolean("controllerToggleCrouch", ArbitrarySupplier.of(new LegacyConfigDisplay<>(Component.translatable("options.key.toggleSneak"))),true, b->{}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> controllerToggleSprint = CLIENT_STORAGE.register(LegacyConfig.createBoolean("controllerToggleSprint", ArbitrarySupplier.of(new LegacyConfigDisplay<>(Component.translatable("options.key.toggleSprint"))),false, b-> {}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> lockControlTypeChange = CLIENT_STORAGE.register(createBoolean("lockControlTypeChange",false));
    public static final LegacyConfig<Integer> selectedItemTooltipLines = CLIENT_STORAGE.register(createInteger("selectedItemTooltipLines", Options::genericValueLabel, 0,()->6, 4));
    public static final LegacyConfig<Boolean> itemTooltipEllipsis = CLIENT_STORAGE.register(createBoolean("itemTooltipEllipsis",true));
    public static final LegacyConfig<Integer> selectedItemTooltipSpacing = CLIENT_STORAGE.register(createInteger("selectedItemTooltipSpacing", Options::genericValueLabel, 8,()->12, 12));
    public static final LegacyConfig<VehicleCameraRotation> vehicleCameraRotation = CLIENT_STORAGE.register(create("vehicleCameraRotation", (c, d) -> CommonComponents.optionNameValue(c, d.displayName), i-> VehicleCameraRotation.values()[i], VehicleCameraRotation::ordinal, ()->VehicleCameraRotation.values().length, VehicleCameraRotation.ONLY_NON_LIVING_ENTITIES, d -> {}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> defaultParticlePhysics = CLIENT_STORAGE.register(createBoolean("defaultParticlePhysics", true));
    public static final LegacyConfig<Boolean> linearCameraMovement = CLIENT_STORAGE.register(createBoolean("linearCameraMovement", false));
    public static final LegacyConfig<Boolean> legacyOverstackedItems = CLIENT_STORAGE.register(createBoolean("legacyOverstackedItems", true));
    public static final LegacyConfig<Boolean> displayMultipleControlsFromAction = CLIENT_STORAGE.register(createBoolean("displayMultipleControlsFromAction", false));
    public static final LegacyConfig<Boolean> enhancedPistonMovingRenderer = CLIENT_STORAGE.register(createBoolean("enhancedPistonMovingRenderer", true));
    public static final LegacyConfig<Boolean> legacyEntityFireTint = CLIENT_STORAGE.register(createBoolean("legacyEntityFireTint", true));
    public static final LegacyConfig<Boolean> advancedHeldItemTooltip = CLIENT_STORAGE.register(createBoolean("advancedHeldItemTooltip", false));
    public static final LegacyConfig<AdvancedOptionsMode> advancedOptionsMode = CLIENT_STORAGE.register(create("advancedOptionsMode", (c, d) -> CommonComponents.optionNameValue(c, d.displayName), i-> AdvancedOptionsMode.values()[i], AdvancedOptionsMode::ordinal, ()->AdvancedOptionsMode.values().length, AdvancedOptionsMode.DEFAULT, d -> {}, CLIENT_STORAGE));
    public static final LegacyConfig<Boolean> hasSaveCache = CLIENT_STORAGE.register(createBoolean("saveCache", true));
    public static final LegacyConfig<Boolean> autoSaveCountdown = CLIENT_STORAGE.register(createBoolean("autoSaveCountdown", true));
    public static final LegacyConfig<Boolean> displayControlTooltips = CLIENT_STORAGE.register(createBoolean("displayControlTooltips", true));

    public enum VehicleCameraRotation implements StringRepresentable {
        NONE("none", LegacyComponents.NONE),ALL_ENTITIES("all_entities"),ONLY_NON_LIVING_ENTITIES("only_non_living_entities"),ONLY_LIVING_ENTITIES("only_living_entities");
        public static final EnumCodec<VehicleCameraRotation> CODEC = StringRepresentable.fromEnum(VehicleCameraRotation::values);
        private final String name;
        public final Component displayName;

        VehicleCameraRotation(String name, Component displayName){
            this.name = name;
            this.displayName = displayName;
        }
        VehicleCameraRotation(String name){
            this(name,Component.translatable("legacy.options.vehicleCameraRotation."+name));
        }
        public boolean isForLivingEntities(){
            return this == ALL_ENTITIES || this == ONLY_LIVING_ENTITIES;
        }
        public boolean isForNonLivingEntities(){
            return this == ALL_ENTITIES || this == ONLY_NON_LIVING_ENTITIES;
        }
        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum AdvancedOptionsMode implements StringRepresentable {
        DEFAULT("default"),MERGE("merge"),HIDE("hide");
        public static final EnumCodec<AdvancedOptionsMode> CODEC = StringRepresentable.fromEnum(AdvancedOptionsMode::values);
        private final String name;
        public final Component displayName;

        AdvancedOptionsMode(String name, Component displayName){
            this.name = name;
            this.displayName = displayName;
        }
        AdvancedOptionsMode(String name) {
            this(name, Component.translatable("legacy.options.advancedOptionsMode." + name));
        }
        @Override
        public String getSerializedName() {
            return name;
        }
    }


    @Deprecated
    private static <T> DataResult<T> parseDeprecatedOptionFromString(String string, LegacyConfig<T> config){
        JsonReader jsonReader = new JsonReader(new StringReader(string));
        JsonElement jsonElement = JsonParser.parseReader(jsonReader);
        DataResult<T> dataResult = config.decode(new Dynamic<>(JsonOps.INSTANCE, jsonElement));
        dataResult.error().ifPresent(error -> Legacy4J.LOGGER.error("Error parsing option value {} for option {}: {}",string,config,error.message()));
        return dataResult;
    }

    @Deprecated
    private static void loadDeprecated(){
        if (!deprecatedLegacyOptionssFile.exists()) return;
        try {
            CompoundTag compoundTag = new CompoundTag();
            BufferedReader bufferedReader = Files.newReader(deprecatedLegacyOptionssFile, Charsets.UTF_8);

            try {
                bufferedReader.lines().forEach(string -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(string).iterator();
                        compoundTag.putString(iterator.next(), iterator.next());
                    } catch (Exception var3) {
                        Legacy4J.LOGGER.warn("Skipping bad option: {}", string);
                    }
                });
            } catch (Throwable var6) {
                try {
                    bufferedReader.close();
                } catch (Throwable var5) {
                    var6.addSuppressed(var5);
                }

                throw var6;
            }
            bufferedReader.close();
            CLIENT_STORAGE.configMap.forEach((s,o)->{
                String value = compoundTag.contains(s) ? compoundTag.getString(s) : null;
                if (value != null) {
                    parseDeprecatedOptionFromString(value.isEmpty() ? "\"\"" : value,o);
                }
            });
        } catch (IOException e) {
            Legacy4J.LOGGER.error("Failed to load options", e);
        }
        deprecatedLegacyOptionssFile.delete();
    }
}
