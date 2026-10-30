package dev.mine2metal.metal;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.mine2metal.Mine2Metal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes Sodium's terrain shader and Minecraft's entity, item and particle shaders also write the world shader's G-buffer, as GLSL before
 * Mojang compiles it to SPIR-V: the surface color without any lighting (for terrain, the vertex tint with Minecraft's
 * baked face shading taken out again), the face normal from the position's screen derivatives (exact for flat faces),
 * and the block and sky light levels. Attachments 1 and 2 only exist while {@link MetalCommandEncoder} draws the opaque
 * terrain, entities and particles, and then the translucent terrain (for the water), of the main world pass; elsewhere Metal
 * drops the extra outputs. Minecraft's OIT variants declare their own attachments and are left alone, as are entity
 * overlays (enchantment glint, glowing eyes).
 */
public final class GbufferShaderPatch {
	/** Marks a fragment shader whose MSL writes the G-buffer. */
	static final String MARKER = "m2m_GAlbedo";

	private static final String OCT_ENCODE = """
			vec2 m2m_octEncode(vec3 n) {
			    n /= abs(n.x) + abs(n.y) + abs(n.z);
			    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
			    return e * 0.5 + 0.5;
			}
			""";

	private record Edit(String anchor, String replacement) {
	}

	private static final List<Edit> SODIUM_VERTEX = List.of(
			new Edit("layout(location = 1) out vec2 v_TexCoord;", """
					layout(location = 1) out vec2 v_TexCoord;
					#ifndef OIT
					layout(location = 4) out vec3 m2m_Position;
					layout(location = 5) out vec4 m2m_Tint;
					layout(location = 6) out vec2 m2m_Light;
					layout(location = 7) flat out uint m2m_Material;
					#endif"""),
			new Edit("gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);", """
					gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
					#ifndef OIT
					    m2m_Position = position;
					    m2m_Tint = _vert_color;
					    m2m_Light = _vert_tex_light_coord;
					    m2m_Material = _material_params;
					#endif"""));

	private static final List<Edit> SODIUM_FRAGMENT = List.of(
			new Edit("layout(location = 1) in vec2 v_TexCoord; // The interpolated block texture coordinates", """
					layout(location = 1) in vec2 v_TexCoord; // The interpolated block texture coordinates
					#ifndef OIT
					layout(location = 4) in vec3 m2m_Position;
					layout(location = 5) in vec4 m2m_Tint;
					layout(location = 6) in vec2 m2m_Light;
					layout(location = 7) flat in uint m2m_Material;
					layout(location = 1) out vec4 m2m_GAlbedo;
					layout(location = 2) out vec4 m2m_GData;
					""" + OCT_ENCODE + """

					void m2m_writeGbuffer(vec4 texel, vec3 n) {
					    if (dot(n, m2m_Position) > 0.0) {
					        n = -n;
					    }
					    // Minecraft's diffuse face shading, which Sodium bakes into the vertex color along with the ambient occlusion.
					    float shade = min(n.x * n.x * 0.6 + n.y * n.y * (3.0 + n.y) * 0.25 + n.z * n.z * 0.8, 1.0);
					    vec3 albedo = min(texel.rgb * m2m_Tint.rgb / max(shade, 0.45), vec3(1.0));
					    // Sodium stores light levels 0..15 as 8..248 out of 256.
					    vec2 light = clamp((m2m_Light * 256.0 - 8.0) / 240.0, 0.0, 1.0);
					    bool cutout = ((m2m_Material >> 1u) & 3u) != 0u;
					    bool tinted = abs(m2m_Tint.r - m2m_Tint.g) + abs(m2m_Tint.g - m2m_Tint.b) > 0.04;
					    // 1 solid, 2 foliage (tinted cutout: leaves, grass, vines), 3 other cutout; +8 when drawn full bright (emissive),
					    // +16 when tinted.
					    float material = cutout ? (tinted ? 2.0 : 3.0) : 1.0;
					    if (light.x > 0.99 && light.y > 0.99) {
					        material += 8.0;
					    }
					    // Tinted by the biome: in the translucent layer that is water (glass and ice are untinted).
					    if (tinted) {
					        material += 16.0;
					    }
					    m2m_GAlbedo = vec4(albedo, material / 255.0);
					    m2m_GData = vec4(m2m_octEncode(n), light);
					}
					#endif"""),
			// The derivatives are taken here, before the cutout discard can make control flow non-uniform.
			new Edit("color *= v_Color; // Apply per-vertex color modulator", """
					vec4 m2m_texel = color;
					#ifndef OIT
					    vec3 m2m_normal = normalize(cross(dFdx(m2m_Position), dFdy(m2m_Position)));
					#endif
					    color *= v_Color; // Apply per-vertex color modulator"""),
			new Edit("fragColor = calculateFinalColor(color);", """
					#ifndef OIT
					    m2m_writeGbuffer(m2m_texel, m2m_normal);
					    #endif
					    fragColor = calculateFinalColor(color);"""));

	/** Entities and block entities, but not their blended overlays, which are left to draw as vanilla does. */
	private static final String ENTITY_GUARD = "#if !defined(OIT) && !defined(GLINT) && !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)";

	private static List<Edit> entityVertex(String texCoordOutput) {
		return List.of(
				new Edit(texCoordOutput, texCoordOutput + "\n" + ENTITY_GUARD + """

						layout(location = 8) out vec3 m2m_Position;
						layout(location = 9) out vec4 m2m_Tint;
						layout(location = 10) out vec2 m2m_Light;
						#endif"""),
				new Edit("    texCoord0 = UV0;\n", """
						    texCoord0 = UV0;
						""" + ENTITY_GUARD + """

						    m2m_Position = Position;
						    m2m_Tint = Color;
						    // Light levels 0..15 as 0..240.
						    m2m_Light = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
						#endif
						"""));
	}

	private static List<Edit> entityFragment(String texCoordInput) {
		return List.of(
				new Edit(texCoordInput, texCoordInput + "\n" + ENTITY_GUARD + """

						layout(location = 8) in vec3 m2m_Position;
						layout(location = 9) in vec4 m2m_Tint;
						layout(location = 10) in vec2 m2m_Light;
						layout(location = 1) out vec4 m2m_GAlbedo;
						layout(location = 2) out vec4 m2m_GData;
						""" + OCT_ENCODE + """
						#endif"""),
				// Before the cutout discard, as for terrain.
				new Edit("    vec4 color = texture(Sampler0, texCoord0);\n", """
						    vec4 color = texture(Sampler0, texCoord0);
						""" + ENTITY_GUARD + """

						    vec4 m2m_texel = color;
						    vec3 m2m_normal = normalize(cross(dFdx(m2m_Position), dFdy(m2m_Position)));
						#endif
						"""),
				new Edit("    fragColor = calculateFinalColor(color);", ENTITY_GUARD + """

						    {
						        vec3 n = dot(m2m_normal, m2m_Position) > 0.0 ? -m2m_normal : m2m_normal;
						        vec4 albedo = m2m_texel * m2m_Tint * ColorModulator;
						        #ifndef NO_OVERLAY
						        // The red flash of a hurt mob, and the white of a creeper about to blow.
						        albedo.rgb = mix(overlayColor.rgb, albedo.rgb, overlayColor.a);
						        #endif
						        // Solid; +8 when drawn full bright.
						        float material = m2m_Light.x > 0.99 && m2m_Light.y > 0.99 ? 9.0 : 1.0;
						        m2m_GAlbedo = vec4(albedo.rgb, material / 255.0);
						        m2m_GData = vec4(m2m_octEncode(n), m2m_Light);
						    }
						#endif
						    fragColor = calculateFinalColor(color);"""));
	}

	private static final String PARTICLE_GUARD = "#if !defined(OIT) && !defined(OIT_ALPHA_ONLY)";

	private static final List<Edit> PARTICLE_VERTEX = List.of(
			new Edit("layout(location = 3) out vec4 vertexColor;", """
					layout(location = 3) out vec4 vertexColor;
					""" + PARTICLE_GUARD + """

					layout(location = 4) out vec4 m2m_Tint;
					layout(location = 5) out vec2 m2m_Light;
					#endif"""),
			new Edit("    vertexColor = Color * sample_lightmap(Sampler2, UV2);", """
					    vertexColor = Color * sample_lightmap(Sampler2, UV2);
					""" + PARTICLE_GUARD + """

					    m2m_Tint = Color;
					    m2m_Light = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
					#endif"""));

	private static final List<Edit> PARTICLE_FRAGMENT = List.of(
			new Edit("layout(location = 3) in vec4 vertexColor;", """
					layout(location = 3) in vec4 vertexColor;
					""" + PARTICLE_GUARD + """

					layout(location = 4) in vec4 m2m_Tint;
					layout(location = 5) in vec2 m2m_Light;
					layout(location = 1) out vec4 m2m_GAlbedo;
					layout(location = 2) out vec4 m2m_GData;
					#endif"""),
			new Edit("    fragColor = calculateFinalColor(color);", PARTICLE_GUARD + """

					    {
					        vec4 albedo = texture(Sampler0, texCoord0) * m2m_Tint * ColorModulator;
					        // Particles face the camera, so they are lit without a direction (material 4, +8 full bright); one
					        // that is see-through here is marked to keep what it drew (material 0).
					        float material = m2m_Light.x > 0.99 && m2m_Light.y > 0.99 ? 12.0 : 4.0;
					        m2m_GAlbedo = vec4(albedo.rgb, albedo.a > 0.9 ? material / 255.0 : 0.0);
					        // Straight up, in the octahedral encoding the other shaders write.
					        m2m_GData = vec4(0.5, 1.0, m2m_Light);
					    }
					#endif
					    fragColor = calculateFinalColor(color);"""));

	private static final Map<String, List<Edit>> VERTEX = Map.of(
			"sodium:blocks/block_layer_opaque", SODIUM_VERTEX,
			"minecraft:core/entity", entityVertex("layout(location = 6) out vec2 texCoord0;"),
			"minecraft:core/item", entityVertex("layout(location = 5) out vec2 texCoord0;"),
			"minecraft:core/particle", PARTICLE_VERTEX);
	private static final Map<String, List<Edit>> FRAGMENT = Map.of(
			"sodium:blocks/block_layer_opaque", SODIUM_FRAGMENT,
			"minecraft:core/entity", entityFragment("layout(location = 6) in vec2 texCoord0;"),
			"minecraft:core/item", entityFragment("layout(location = 5) in vec2 texCoord0;"),
			"minecraft:core/particle", PARTICLE_FRAGMENT);

	private static final Set<String> warned = new HashSet<>();

	private GbufferShaderPatch() {
	}

	/** The source with the G-buffer writes added; any other source, or one that no longer matches, unchanged. */
	public static String patch(String name, ShaderType type, String source) {
		List<Edit> edits = (type == ShaderType.VERTEX ? VERTEX : FRAGMENT).get(name);
		if (edits == null) {
			return source;
		}
		String patched = source;
		for (Edit edit : edits) {
			if (!patched.contains(edit.anchor())) {
				if (warned.add(name)) {
					Mine2Metal.LOGGER.warn("Shader {} changed; the Metal shaders cannot read its surfaces and leave them as vanilla draws them", name);
				}
				return source;
			}
			patched = patched.replace(edit.anchor(), edit.replacement());
		}
		return patched;
	}
}
