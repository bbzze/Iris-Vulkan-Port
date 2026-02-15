Place VulkanMod jar file here for compilation.

To build VulkanMod:
1. cd ../VulkanMod-1.21
2. gradlew build
3. Copy build/libs/VulkanMod_1.21-*.jar to this directory

The Iris Vulkan Port compiles against VulkanMod's classes (modCompileOnly).
At runtime, VulkanMod is loaded as a separate Fabric mod.
