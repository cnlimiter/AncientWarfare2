package net.shadowmage.ancientwarfare.structure.template;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.shadowmage.ancientwarfare.core.gamedata.AWGameData;
import net.shadowmage.ancientwarfare.structure.AncientWarfareStructure;
import net.shadowmage.ancientwarfare.structure.config.AWStructureStatics;
import net.shadowmage.ancientwarfare.structure.gamedata.StructureEntry;
import net.shadowmage.ancientwarfare.structure.gamedata.StructureMap;
import net.shadowmage.ancientwarfare.structure.registry.BiomeGroupRegistry;
import net.shadowmage.ancientwarfare.structure.template.build.validation.StructureValidator;
import net.shadowmage.ancientwarfare.structure.util.CollectionUtils;
import net.shadowmage.ancientwarfare.structure.worldgen.Territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static net.shadowmage.ancientwarfare.structure.template.build.validation.properties.StructureValidationProperties.TERRITORY_NAME;

public class WorldGenStructureManager {
	public static final String GENERIC_TERRITORY_NAME = "";
	private HashMap<String, Set<StructureTemplate>> templatesByBiome = new HashMap<>();
	private HashMap<Biome, List<String>> territoryNamesByBiome = new HashMap<>();
	private HashMap<String, Set<Biome>> biomesByTerritoryNames = new HashMap<>();
	private HashMap<String, Set<StructureTemplate>> templatesByTerritoryName = new HashMap<>();

	/*
	 * cached list objects, used for temp searching, as to not allocate new lists for every chunk-generated....
	 */
	private List<StructureEntry> searchCache = new ArrayList<>();
	private List<StructureTemplate> trimmedPotentialStructures = new ArrayList<>();
	private HashMap<String, Integer> distancesFound = new HashMap<>();

	public static final WorldGenStructureManager INSTANCE = new WorldGenStructureManager();

	private WorldGenStructureManager() {
	}

	public Optional<List<String>> getBiomeTerritoryNames(Biome biome) {
		return Optional.ofNullable(territoryNamesByBiome.get(biome));
	}

	public Optional<Set<Biome>> getTerritoryBiomes(String territoryName) {
		return Optional.ofNullable(biomesByTerritoryNames.get(territoryName));
	}

	public void clearCachedTemplates() {
		templatesByBiome.clear();
		territoryNamesByBiome.clear();
		biomesByTerritoryNames.clear();
		templatesByTerritoryName.clear();
	}

	public void loadBiomeList() {
		for (Biome biome : Biome.REGISTRY) {
			if (biome == null) {
				continue;
			}
			//noinspection ConstantConditions
			templatesByBiome.put(biome.getRegistryName().toString(), new HashSet<>());
		}
	}

	public void registerWorldGenStructure(StructureTemplate template) {
		StructureValidator validation = template.getValidationSettings();
		Set<String> biomes = validation.getBiomeList();
		Set<String> biomeGroupBiomes = new HashSet<>();
		validation.getBiomeGroupList().forEach(biomeGroup -> biomeGroupBiomes.addAll(BiomeGroupRegistry.getGroupBiomes(biomeGroup)));

		String territoryName = template.getValidationSettings().getPropertyValue(TERRITORY_NAME);
		Set<StructureTemplate> templates = templatesByTerritoryName.getOrDefault(territoryName, new HashSet<>());
		templates.add(template);
		templatesByTerritoryName.put(territoryName, templates);

		if (validation.isBiomeWhiteList()) {
			whitelistBiomes(template, biomes, biomeGroupBiomes, territoryName);
		} else {
			blacklistBiomes(template, biomes, biomeGroupBiomes, territoryName);
		}
	}

	private void whitelistBiomes(StructureTemplate template, Set<String> biomes, Set<String> biomeGroupBiomes, String territoryName) {
		addTemplateToBiomes(template, biomeGroupBiomes, b -> true, territoryName);
		addTemplateToBiomes(template, biomes, b -> biomeGroupBiomes.isEmpty() || biomeGroupBiomes.contains(b), territoryName);
	}

	private void addTemplateToBiomes(StructureTemplate template, Set<String> biomeGroupBiomes, Predicate<String> checkBiome, String territoryName) {
		for (String biomeName : biomeGroupBiomes) {
			if (templatesByBiome.containsKey(biomeName) && checkBiome.test(biomeName)) {
				addBiomeTemplate(template, territoryName, biomeName);
			} else if (Loader.isModLoaded((new ResourceLocation(biomeName)).getResourceDomain())) {
				AncientWarfareStructure.LOG.warn("Could not locate biome: {} while registering template: {} for world generation.", biomeName, template.name);
			}
		}
	}

	private void addBiomeTemplate(StructureTemplate template, String territoryName, String biomeName) {
		templatesByBiome.get(biomeName).add(template);
		Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(biomeName));
		if (biome != null) {
			List<String> territoryNames = territoryNamesByBiome.getOrDefault(biome, new ArrayList<>());
			if (!territoryNames.contains(territoryName)) {
				territoryNames.add(territoryName);
				territoryNamesByBiome.put(biome, territoryNames);
			}

			Set<Biome> biomes = biomesByTerritoryNames.getOrDefault(territoryName, new HashSet<>());
			biomes.add(biome);
			biomesByTerritoryNames.put(territoryName, biomes);
		}
	}

	private void blacklistBiomes(StructureTemplate template, Set<String> biomes, Set<String> biomeGroupBiomes, String territoryName) {
		Set<String> biomesBaseList = biomeGroupBiomes.isEmpty() ? templatesByBiome.keySet() : biomeGroupBiomes;
		for (String biome : biomesBaseList) {
			if (!biomes.isEmpty() && biomes.contains(biome)) {
				continue;
			}
			if (templatesByBiome.containsKey(biome)) {
				addBiomeTemplate(template, territoryName, biome);
			}
		}
	}

	public Optional<StructureTemplate> selectTemplateForGeneration(World world, Random rng, int x, int y, int z, EnumFacing face, Territory territory) {
		searchCache.clear();
		trimmedPotentialStructures.clear();
		distancesFound.clear();
		StructureMap map = AWGameData.INSTANCE.getPerWorldData(world, StructureMap.class);

		Biome biome = world.provider.getBiomeForCoords(new BlockPos(x, 1, z));

		//noinspection ConstantConditions
		String biomeName = biome.getRegistryName().toString();
		Set<StructureTemplate> potentialStructures = new HashSet<>();
		getTerritoryTemplates(territory.getTerritoryName()).ifPresent(potentialStructures::addAll);
		getTerritoryTemplates(GENERIC_TERRITORY_NAME).ifPresent(potentialStructures::addAll);
		Set<StructureTemplate> biomeTemplates = templatesByBiome.get(biomeName);
		potentialStructures.removeIf(t -> !biomeTemplates.contains(t));
		if (potentialStructures.isEmpty()) {
			return Optional.empty();
		}

		int remainingValueCache = (int) (AWStructureStatics.chunkClusterValue * territory.getNumberOfChunks()) - territory.getTotalClusterValue();

		int duplicateSearchDistance = 0;
		for (StructureTemplate template : potentialStructures) {
			duplicateSearchDistance = Math.max(duplicateSearchDistance, template.getValidationSettings().getMinDuplicateDistance());
		}

		Collection<StructureEntry> duplicateSearchEntries = map.getEntriesNear(world, x, z, duplicateSearchDistance / 16, false, searchCache);
		for (StructureEntry entry : duplicateSearchEntries) {
			int mx = entry.getBB().getCenterX() - x;
			int mz = entry.getBB().getCenterZ() - z;
			int foundDistance = (int) MathHelper.sqrt((float) mx * mx + mz * mz);
			if (distancesFound.containsKey(entry.getName())) {
				int dist = distancesFound.get(entry.getName());
				if (foundDistance < dist) {
					distancesFound.put(entry.getName(), foundDistance);
				}
			} else {
				distancesFound.put(entry.getName(), foundDistance);
			}
		}

		int dim = world.provider.getDimension();
		for (StructureTemplate template : potentialStructures)//loop through initial structures, only adding to 2nd list those which meet biome, unique, value, and minDuplicate distance settings
		{
			if (validateTemplate(world, x, y, z, face, map, remainingValueCache, dim, template)) {
				trimmedPotentialStructures.add(template);
			}
		}
		if (trimmedPotentialStructures.isEmpty()) {
			return Optional.empty();
		}
		StructureTemplate toReturn = CollectionUtils.getWeightedRandomElement(rng, this.trimmedPotentialStructures, e -> getStructureWeight(x, y, z, territory, e)).orElse(null);
		distancesFound.clear();
		trimmedPotentialStructures.clear();
		return Optional.of(toReturn);
	}

	private int getStructureWeight(int x, int y, int z, Territory territory, StructureTemplate e) {
		boolean bigStructure = e.getValidationSettings().getClusterValue() > 50;
		int weight = e.getValidationSettings().getSelectionWeight();
		if (bigStructure) {
			return Math.max(0, (int) (weight - ((territory.getTerritoryCenter().distanceSq(x, y, z) / AWStructureStatics.maxTerritoryCenterDistanceSq) * weight)));
		}
		return weight;
	}

	private boolean validateTemplate(World world, int x, int y, int z, EnumFacing face, StructureMap map, int remainingValueCache, int dim, StructureTemplate template) {
		StructureValidator settings = template.getValidationSettings();
		boolean dimensionMatch = !settings.isDimensionWhiteList();
		for (int i = 0; i < settings.getAcceptedDimensions().length; i++) {
			int dimTest = settings.getAcceptedDimensions()[i];
			if (dimTest == dim) {
				dimensionMatch = !dimensionMatch;
				break;
			}
		}
		if (!dimensionMatch)//skip if dimension is blacklisted, or not present on whitelist
		{
			return false;
		}
		if (settings.isUnique() && map.isGeneratedUnique(template.name)) {
			return false;
		}//skip already generated uniques
		if (settings.getClusterValue() > remainingValueCache) {
			return false;
		}//skip if cluster value is to high to place in given area
		if (distancesFound.containsKey(template.name)) {
			int dist = distancesFound.get(template.name);
			if (dist < settings.getMinDuplicateDistance()) {
				return false;
			}//skip if minDuplicate distance is not met
		}
		return settings.shouldIncludeForSelection(world, x, y, z, face, template);
	}

	public Optional<Set<StructureTemplate>> getTerritoryTemplates(String territoryName) {
		return Optional.ofNullable(templatesByTerritoryName.get(territoryName));
	}
}
