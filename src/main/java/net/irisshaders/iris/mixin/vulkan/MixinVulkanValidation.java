package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.Iris;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Mixin to enable Vulkan validation layers for debugging.
 * Checks if VK_LAYER_KHRONOS_validation is available before enabling.
 * If not installed (no Vulkan SDK), gracefully skips.
 */
@Mixin(value = Vulkan.class, remap = false)
public class MixinVulkanValidation {

	@Shadow
	private static VkInstance instance;

	@Unique
	private static VkDebugUtilsMessengerCallbackEXT iris$debugCallback;

	@Unique
	private static long iris$debugMessenger = VK_NULL_HANDLE;

	@Unique
	private static boolean iris$validationAvailable = false;

	@Unique
	private static boolean iris$checkValidationLayerSupport() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer layerCount = stack.ints(0);
			vkEnumerateInstanceLayerProperties(layerCount, null);
			if (layerCount.get(0) == 0) return false;

			VkLayerProperties.Buffer layers = VkLayerProperties.malloc(layerCount.get(0), stack);
			vkEnumerateInstanceLayerProperties(layerCount, layers);

			Set<String> available = new HashSet<>();
			for (int i = 0; i < layers.capacity(); i++) {
				available.add(layers.get(i).layerNameString());
			}
			return available.contains("VK_LAYER_KHRONOS_validation");
		}
	}

	@Redirect(method = "createInstance",
		at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK10;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static int iris$addValidation(VkInstanceCreateInfo createInfo, VkAllocationCallbacks allocator, PointerBuffer pInstance) {
		// Check if validation layer is available before trying to enable it
		iris$validationAvailable = iris$checkValidationLayerSupport();

		if (!iris$validationAvailable) {
			Iris.logger.warn("[IRIS] VK_LAYER_KHRONOS_validation NOT available. Install the Vulkan SDK to enable validation layers.");
			Iris.logger.warn("[IRIS] Download from: https://vulkan.lunarg.com/sdk/home");
			return VK10.vkCreateInstance(createInfo, allocator, pInstance);
		}

		MemoryStack stack = MemoryStack.stackGet();

		// Add validation layer
		PointerBuffer layers = stack.mallocPointer(1);
		layers.put(0, stack.UTF8("VK_LAYER_KHRONOS_validation"));
		createInfo.ppEnabledLayerNames(layers);

		// Add VK_EXT_debug_utils extension to existing extensions
		PointerBuffer existingExts = createInfo.ppEnabledExtensionNames();
		int extCount = existingExts != null ? existingExts.remaining() : 0;
		PointerBuffer newExts = stack.mallocPointer(extCount + 1);
		for (int i = 0; i < extCount; i++) {
			newExts.put(i, existingExts.get(i));
		}
		newExts.put(extCount, stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
		createInfo.ppEnabledExtensionNames(newExts);

		// Create debug callback
		iris$debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, type, pCallbackData, pUserData) -> {
			VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
			String msg = data.pMessageString();
			if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
				Iris.logger.error("[VK-VALIDATION] {}", msg);
			} else {
				Iris.logger.warn("[VK-VALIDATION] {}", msg);
			}
			return VK_FALSE;
		});

		// Attach debug messenger as pNext
		VkDebugUtilsMessengerCreateInfoEXT debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
		debugInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
		debugInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
		debugInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
		debugInfo.pfnUserCallback(iris$debugCallback);
		createInfo.pNext(debugInfo.address());

		Iris.logger.info("[IRIS] Vulkan validation layers ENABLED");
		return VK10.vkCreateInstance(createInfo, allocator, pInstance);
	}

	@Inject(method = "createInstance", at = @At("TAIL"))
	private static void iris$setupPersistentDebugMessenger(CallbackInfo ci) {
		if (!iris$validationAvailable || instance == null) return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
			createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
			createInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
			createInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
			createInfo.pfnUserCallback(iris$debugCallback);

			LongBuffer pMessenger = stack.longs(VK_NULL_HANDLE);
			int result = vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pMessenger);
			if (result == VK_SUCCESS) {
				iris$debugMessenger = pMessenger.get(0);
				Iris.logger.info("[IRIS] Vulkan debug messenger active — errors appear as [VK-VALIDATION]");
			} else {
				Iris.logger.warn("[IRIS] Failed to create debug messenger: result={}", result);
			}
		}
	}
}
