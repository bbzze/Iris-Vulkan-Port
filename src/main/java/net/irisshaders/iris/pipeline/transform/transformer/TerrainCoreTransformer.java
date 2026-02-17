package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.TerrainParameters;

public class TerrainCoreTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		TerrainParameters parameters) {
		root.rename("alphaTestRef", "iris_currentAlphaTest");
		root.rename("modelViewMatrix", "iris_ModelViewMatrix");
		root.rename("modelViewMatrixInverse", "iris_ModelViewMatrixInverse");
		root.rename("projectionMatrix", "iris_ProjectionMatrix");
		root.rename("projectionMatrixInverse", "iris_ProjectionMatrixInverse");
		root.rename("normalMatrix", "iris_NormalMatrix");
		root.rename("chunkOffset", "u_RegionOffset");

		if (parameters.type == PatchShaderType.VERTEX) {
			// _draw_translation replaced with Chunks[_draw_id].offset.xyz
			root.replaceReferenceExpressions(t, "vaPosition", "_vert_position + _get_draw_translation(_draw_id)");
			root.replaceReferenceExpressions(t, "vaColor", "_vert_color");
			root.rename("vaNormal", "iris_Normal");
			root.replaceReferenceExpressions(t, "vaUV0", "_vert_tex_diffuse_coord");
			root.replaceReferenceExpressions(t, "vaUV1", "ivec2(0, 10)");
			root.rename("vaUV2", "a_LightCoord");

			root.replaceReferenceExpressions(t, "textureMatrix", "mat4(1.0)");

			TerrainTransformer.injectVertInit(t, tree, root, parameters);
		}

		// Gbuffer fragment shaders use standard Vulkan viewport (Y=0 at top),
		// but shader packs assume OpenGL convention (Y=0 at bottom). When the shader
		// reconstructs view-space position via gbufferProjectionInverse * screenPos,
		// the Y direction is inverted. Negate column 1 (Y) of projection matrices
		// to compensate, matching what CompositeTransformer does for composite passes.
		if (parameters.type == PatchShaderType.FRAGMENT) {
			boolean needsHelper = root.identifierIndex.has("gbufferProjection")
				|| root.identifierIndex.has("gbufferProjectionInverse")
				|| root.identifierIndex.has("gbufferPreviousProjection");
			if (needsHelper) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					"mat4 iris_flipProjY(mat4 p) { p[1] = -p[1]; return p; }");
			}
			if (root.identifierIndex.has("gbufferProjection")) {
				root.replaceReferenceExpressions(t, "gbufferProjection", "iris_flipProjY(gbufferProjection)");
			}
			if (root.identifierIndex.has("gbufferProjectionInverse")) {
				root.replaceReferenceExpressions(t, "gbufferProjectionInverse", "iris_flipProjY(gbufferProjectionInverse)");
			}
			if (root.identifierIndex.has("gbufferPreviousProjection")) {
				root.replaceReferenceExpressions(t, "gbufferPreviousProjection", "iris_flipProjY(gbufferPreviousProjection)");
			}
		}
	}
}
