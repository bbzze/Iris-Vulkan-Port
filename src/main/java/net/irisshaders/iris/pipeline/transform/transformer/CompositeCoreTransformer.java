package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

public class CompositeCoreTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		Parameters parameters) {
		CompositeDepthTransformer.transform(t, tree, root);

		if (parameters.type == PatchShaderType.VERTEX) {
			root.rename("vaPosition", "Position");
			root.rename("vaUV0", "UV0");
			root.replaceReferenceExpressions(t, "modelViewMatrix", "mat4(1.0)");
			// This is used to scale the quad projection matrix from (0, 1) to (-1, 1).
			root.replaceReferenceExpressions(t, "projectionMatrix",
				"mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))");
			root.replaceReferenceExpressions(t, "modelViewMatrixInverse", "mat4(1.0)");
			root.replaceReferenceExpressions(t, "projectionMatrixInverse",
				"inverse(mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0)))");
			root.replaceReferenceExpressions(t, "textureMatrix", "mat4(1.0)");
		}

		// Composite/deferred fragment shaders use standard Vulkan viewport (Y=0 at top),
		// but scene rendering uses the flipped viewport (Y=0 at bottom). Negate column 1
		// (Y) of projection matrices so position reconstruction matches scene clip space.
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
