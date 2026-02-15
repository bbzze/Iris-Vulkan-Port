package net.irisshaders.iris.gl.shader;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.GlUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.texture.format.TextureFormat;
import net.irisshaders.iris.texture.format.TextureFormatLoader;
import net.minecraft.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard macros - Vulkan Port.
 *
 * Provides #define macros to shader packs. GL version/extension queries
 * replaced with Vulkan-compatible equivalents. Instead of querying
 * GL_VERSION and GL_SHADING_LANGUAGE_VERSION, we report Vulkan-equivalent
 * values (GL 4.6 / GLSL 4.60 since we compile to SPIR-V via GLSL 460).
 *
 * GL extension queries are replaced with a predefined set of extensions
 * that are effectively always available on Vulkan implementations.
 */
public class StandardMacros {
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.*(?<bugfix>\\d*)(.*)");

	private static void define(List<StringPair> defines, String key) {
		defines.add(new StringPair(key, ""));
	}

	private static void define(List<StringPair> defines, String key, String value) {
		defines.add(new StringPair(key, value));
	}

	public static ImmutableList<StringPair> createStandardEnvironmentDefines() {
		ArrayList<StringPair> standardDefines = new ArrayList<>();

		define(standardDefines, "MC_VERSION", getMcVersion());
		define(standardDefines, "IRIS_VERSION", getFormattedIrisVersion());
		// In Vulkan, we report GL 4.6 equivalent (since GLSL 460 is used for SPIR-V compilation)
		define(standardDefines, "MC_GL_VERSION", getVulkanGlVersionEquivalent());
		define(standardDefines, "MC_GLSL_VERSION", getVulkanGlslVersionEquivalent());
		define(standardDefines, getOsString());
		define(standardDefines, getVendor());
		define(standardDefines, getRenderer());
		define(standardDefines, "IS_IRIS");
		define(standardDefines, "IRIS_TAG_SUPPORT", "2");


		if (FabricLoader.getInstance().isModLoaded("distanthorizons") && DHCompat.hasRenderingEnabled()) {
			define(standardDefines, "DISTANT_HORIZONS");
		}

		if (FabricLoader.getInstance().isModLoaded("continuity")) {
			define(standardDefines, "IRIS_HAS_CONNECTED_TEXTURES");
		}

		define(standardDefines, "DH_BLOCK_UNKNOWN", String.valueOf(0));
		define(standardDefines, "DH_BLOCK_LEAVES", String.valueOf(1));
		define(standardDefines, "DH_BLOCK_STONE", String.valueOf(2));
		define(standardDefines, "DH_BLOCK_WOOD", String.valueOf(3));
		define(standardDefines, "DH_BLOCK_METAL", String.valueOf(4));
		define(standardDefines, "DH_BLOCK_DIRT", String.valueOf(5));
		define(standardDefines, "DH_BLOCK_LAVA", String.valueOf(6));
		define(standardDefines, "DH_BLOCK_DEEPSLATE", String.valueOf(7));
		define(standardDefines, "DH_BLOCK_SNOW", String.valueOf(8));
		define(standardDefines, "DH_BLOCK_SAND", String.valueOf(9));
		define(standardDefines, "DH_BLOCK_TERRACOTTA", String.valueOf(10));
		define(standardDefines, "DH_BLOCK_NETHER_STONE", String.valueOf(11));
		define(standardDefines, "DH_BLOCK_WATER", String.valueOf(12));
		define(standardDefines, "DH_BLOCK_GRASS", String.valueOf(13));
		define(standardDefines, "DH_BLOCK_AIR", String.valueOf(14));
		define(standardDefines, "DH_BLOCK_ILLUMINATED", String.valueOf(15));

		for (String glExtension : getVulkanEquivalentExtensions()) {
			define(standardDefines, glExtension);
		}

		define(standardDefines, "MC_NORMAL_MAP");
		define(standardDefines, "MC_SPECULAR_MAP");
		define(standardDefines, "MC_RENDER_QUALITY", "1.0");
		define(standardDefines, "MC_SHADOW_QUALITY", "1.0");
		define(standardDefines, "MC_HAND_DEPTH", Float.toString(HandRenderer.DEPTH));

		TextureFormat textureFormat = TextureFormatLoader.getFormat();
		if (textureFormat != null) {
			for (String define : textureFormat.getDefines()) {
				define(standardDefines, define);
			}
		}

		getRenderStages().forEach((stage, index) -> define(standardDefines, stage, index));

		for (String irisDefine : getIrisDefines()) {
			define(standardDefines, irisDefine);
		}

		return ImmutableList.copyOf(standardDefines);
	}


	/**
	 * Gets the current mc version String in a 5 digit format
	 *
	 * @return mc version string
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L696-L699">Optifine Doc</a>
	 */
	public static String getMcVersion() {
		String version = Iris.getReleaseTarget();
		if (version == null) {
			throw new IllegalStateException("Could not get the current Minecraft version!");
		}
		String formattedVersion = formatVersionString(version);
		if (formattedVersion == null) {
			Iris.logger.error("Could not parse game version \"" + version + "\"");
		} else {
			return formattedVersion;
		}
		String backupVersion = Iris.getBackupVersionNumber();
		String formattedBackupVersion = formatVersionString(backupVersion);
		if (formattedBackupVersion == null) {
			throw new IllegalArgumentException("Could not parse backup game version \"" + version + "\"");
		} else {
			return formattedBackupVersion;
		}
	}


	/**
	 * Gets the current Iris version String in a 5 digit format
	 *
	 * @return The Iris version string
	 *
	 */
	public static String getFormattedIrisVersion() {
		String rawVersion = Iris.getVersion();
		if (rawVersion == null) {
			throw new IllegalArgumentException("Could not get current Iris version!");
		}
		Matcher matcher = SEMVER_PATTERN.matcher(rawVersion);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Could not parse semantic Iris version from \"" + rawVersion + "\"");
		}
		String major = matcher.group("major");
		String minor = matcher.group("minor");
		String bugFix = matcher.group("bugfix");
		if (bugFix == null) {
			bugFix = "0";
		}
		if (major == null || minor == null) {
			throw new IllegalArgumentException("Could not parse semantic Iris version from \"" + rawVersion + "\"");
		}
		String irisSemver = "%s.%s.%s".formatted(major, minor, bugFix);
		String formattedSemver = formatVersionString(irisSemver);
		if (formattedSemver == null) {
			throw new IllegalArgumentException("Could not get a valid semantic version string for Iris version \"" + irisSemver + "\"");
		} else {
			return formattedSemver;
		}
	}

	/**
	 *
	 * Formats a semver string into a 122 format
	 *
	 * @param version The string version to format
	 * @return the formatted version in a 122 format, or <b>null</b> if the string is not a valid semver string.
	 */
	@Nullable
	public static String formatVersionString(String version) {
		String[] splitVersion = version.split("\\.");
		if (splitVersion.length < 2) {
			return null;
		}
		String major = splitVersion[0];
		String minor = splitVersion[1].length() == 1 ? 0 + splitVersion[1] : splitVersion[1];
		String bugFix = splitVersion.length < 3 ? "00" : splitVersion[2];
		if (bugFix.length() == 1) {
			bugFix = 0 + bugFix;
		}
		return major + minor + bugFix;
	}

	/**
	 * Returns the Vulkan-equivalent GL version string.
	 * Since we compile GLSL 460 to SPIR-V, we report GL 4.6 equivalent.
	 *
	 * @return GL version equivalent string in Iris format (major + minor*10 + bugfix)
	 */
	public static String getVulkanGlVersionEquivalent() {
		// Report as OpenGL 4.6 equivalent (Vulkan 1.2 supports all GL 4.6 features)
		return "460";
	}

	/**
	 * Returns the Vulkan-equivalent GLSL version string.
	 * Since we use GLSL 460 for SPIR-V compilation via shaderc.
	 *
	 * @return GLSL version equivalent string
	 */
	public static String getVulkanGlslVersionEquivalent() {
		// GLSL 4.60 - the version we compile to SPIR-V with
		return "460";
	}

	/**
	 * Expanded version of {@link Matcher#group(String)} that does not throw an exception.
	 * If the argument is incorrect (normally resulting in an exception), it returns null
	 *
	 * @param matcher matcher to check the group by
	 * @param name    name of the group
	 * @return the section of the matcher that is a group, or null, if that matcher does not contain said group
	 */
	public static String group(Matcher matcher, String name) {
		try {
			return matcher.group(name);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return null;
		}
	}

	/**
	 * Returns the current OS String
	 *
	 * @return the string based on the current OS
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L709-L714">Optifine Doc</a>
	 */
	public static String getOsString() {
		return switch (Util.getPlatform()) {
			case OSX -> "MC_OS_MAC";
			case LINUX -> "MC_OS_LINUX";
			case WINDOWS ->
				"MC_OS_WINDOWS"; // Note: Optifine doesn't have a macro for Solaris. https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L709-L714
			default -> "MC_OS_UNKNOWN";
		};
	}

	/**
	 * Returns a string indicating the graphics card being used.
	 * GlUtil.getVendor() still works in VulkanMod context as it queries system info.
	 *
	 * @return the graphics card prefixed with "MC_GL_VENDOR_"
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L716-L723">Optifine Doc</a>
	 */
	public static String getVendor() {
		String vendor;
		try {
			vendor = Objects.requireNonNull(GlUtil.getVendor()).toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			// Fallback if GlUtil is not available in Vulkan context
			return "MC_GL_VENDOR_OTHER";
		}
		if (vendor.startsWith("ati")) {
			return "MC_GL_VENDOR_ATI";
		} else if (vendor.startsWith("intel")) {
			return "MC_GL_VENDOR_INTEL";
		} else if (vendor.startsWith("nvidia")) {
			return "MC_GL_VENDOR_NVIDIA";
		} else if (vendor.startsWith("amd")) {
			return "MC_GL_VENDOR_AMD";
		} else if (vendor.startsWith("x.org")) {
			return "MC_GL_VENDOR_XORG";
		}
		return "MC_GL_VENDOR_OTHER";
	}

	/**
	 * Returns the graphics driver being used.
	 * GlUtil.getRenderer() still works in VulkanMod context as it queries system info.
	 *
	 * @return graphics driver prefixed with "MC_GL_RENDERER_"
	 * @see <a href="https://github.com/sp614x/optifine/blob/9c6a5b5326558ccc57c6490b66b3be3b2dc8cbef/OptiFineDoc/doc/shaders.txt#L725-L733">Optifine Doc</a>
	 */
	public static String getRenderer() {
		String renderer;
		try {
			renderer = Objects.requireNonNull(GlUtil.getRenderer()).toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			// Fallback if GlUtil is not available in Vulkan context
			return "MC_GL_RENDERER_OTHER";
		}
		if (renderer.startsWith("amd")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("ati")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("radeon")) {
			return "MC_GL_RENDERER_RADEON";
		} else if (renderer.startsWith("gallium")) {
			return "MC_GL_RENDERER_GALLIUM";
		} else if (renderer.startsWith("intel")) {
			return "MC_GL_RENDERER_INTEL";
		} else if (renderer.startsWith("geforce")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("nvidia")) {
			return "MC_GL_RENDERER_GEFORCE";
		} else if (renderer.startsWith("quadro")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("nvs")) {
			return "MC_GL_RENDERER_QUADRO";
		} else if (renderer.startsWith("mesa")) {
			return "MC_GL_RENDERER_MESA";
		} else if (renderer.startsWith("apple")) {
			return "MC_GL_RENDERER_APPLE";
		}
		return "MC_GL_RENDERER_OTHER";
	}

	/**
	 * Returns a predefined set of GL extensions that are effectively always
	 * available on Vulkan implementations. Since we can't query GL extensions
	 * in a Vulkan context, we provide a comprehensive set that covers what
	 * most shader packs expect.
	 *
	 * @return set of extensions prefixed with "MC_"
	 */
	public static Set<String> getVulkanEquivalentExtensions() {
		// These are GL extensions that have Vulkan equivalents and are
		// effectively always available on modern Vulkan implementations
		Set<String> extensions = new HashSet<>();
		extensions.add("MC_GL_ARB_shader_texture_lod");
		extensions.add("MC_GL_ARB_shading_language_420pack");
		extensions.add("MC_GL_ARB_shading_language_packing");
		extensions.add("MC_GL_ARB_texture_gather");
		extensions.add("MC_GL_ARB_texture_query_levels");
		extensions.add("MC_GL_ARB_texture_query_lod");
		extensions.add("MC_GL_ARB_gpu_shader5");
		extensions.add("MC_GL_ARB_gpu_shader_fp64");
		extensions.add("MC_GL_ARB_explicit_attrib_location");
		extensions.add("MC_GL_ARB_explicit_uniform_location");
		extensions.add("MC_GL_ARB_uniform_buffer_object");
		extensions.add("MC_GL_ARB_shader_storage_buffer_object");
		extensions.add("MC_GL_ARB_shader_image_load_store");
		extensions.add("MC_GL_ARB_conservative_depth");
		extensions.add("MC_GL_ARB_enhanced_layouts");
		extensions.add("MC_GL_ARB_separate_shader_objects");
		extensions.add("MC_GL_ARB_derivative_control");
		extensions.add("MC_GL_ARB_draw_buffers");
		extensions.add("MC_GL_ARB_geometry_shader4");
		extensions.add("MC_GL_ARB_tessellation_shader");
		extensions.add("MC_GL_ARB_compute_shader");
		extensions.add("MC_GL_EXT_gpu_shader4");
		extensions.add("MC_GL_EXT_shader_texture_lod");
		extensions.add("MC_GL_EXT_texture_array");
		return extensions;
	}


	public static Map<String, String> getRenderStages() {
		Map<String, String> stages = new HashMap<>();
		for (WorldRenderingPhase phase : WorldRenderingPhase.values()) {
			stages.put("MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal()));
		}
		return stages;
	}

	/**
	 * Returns the list of Iris-exclusive uniforms supported in the current version of Iris.
	 *
	 * @return List of definitions corresponding to the uniform names prefixed with "MC_"
	 */
	public static List<String> getIrisDefines() {
		List<String> defines = new ArrayList<>();
		// All Iris-exclusive uniforms should have a corresponding definition here. Example:
		// defines.add("MC_UNIFORM_DRAGON_DEATH_PROGRESS");

		return defines;
	}
}
