package org.bimserver.gltf;

/******************************************************************************
 * Copyright (C) 2009-2018  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.antlr.tool.BuildDependencyGenerator;
import org.bimserver.emf.IdEObject;
import org.bimserver.geometry.IfcColors;
import org.bimserver.geometry.Matrix;
import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcObjectDefinition;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcRelAggregates;
import org.bimserver.models.ifc2x3tc1.IfcRelContainedInSpatialStructure;
import org.bimserver.models.ifc2x3tc1.IfcRelDecomposes;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcSpatialStructureElement;
import org.bimserver.models.ifc2x3tc1.IfcSite;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.bimserver.plugins.serializers.ProgressReporter;
import org.bimserver.plugins.serializers.SerializerException;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.LittleEndianDataOutputStream;

/**
 * @author Ruben de Laat
 * 
 *         Annoying things about glTF so far: - Total and scene length have to
 *         be computed in advance, so no streaming is possible
 * 
 */
public class BinaryGltfSerializer2 extends EmfSerializer {

	private static final int FLOAT_VEC_4 = 35666;
	// private static final int SHORT = 5122;
	private static final int ARRAY_BUFFER = 34962;
	private static final int ELEMENT_ARRAY_BUFFER = 34963;
	private static final int MAGIC = 0x46546C67;
	private static final int UNSIGNED_SHORT = 5123;
	private static final int TRIANGLES = 4;
	private static final int FLOAT = 5126;
	private static final int JSON_CHUNK = 0x4E4F534A;
	private static final int BINARY_CHUNK = 0x004E4942;
	private static final int FORMAT_VERSION = 2;
	private static final String GLTF_VERSION = "2.0";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private ArrayNode buffers;
	private ByteBuffer body;
	private ArrayNode meshes;
	private ArrayNode accessors;
	private int accessorCounter = 0;
	private ArrayNode buffersViews;
	private ArrayNode defaultSceneNodes;
	private ArrayNode scenesNode;
	private ObjectNode gltfNode;
	private ArrayNode nodes;

	private byte[] vertexColorFragmentShaderBytes;
	private byte[] vertexColorVertexShaderBytes;
	private byte[] materialColorFragmentShaderBytes;
	private byte[] materialColorVertexShaderBytes;

	float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
	float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
	private ArrayNode modelTranslation;
	private ArrayNode materials;
	private ObjectNode shaders;

	private final Map<String, Integer> createdMaterials = new HashMap<>();
	private ArrayNode translationChildrenNode;
	private int vertexColorIndex;
	enum ControlMode {
		  METADATA,
		  PART,
		  FULL
		}
	private ControlMode controlMode = ControlMode.METADATA;
	private List<String> partIDs = new ArrayList<String>();

	public BinaryGltfSerializer2(byte[] vertexColorFragmentShaderBytes,
			byte[] vertexColorVertexShaderBytes,
			byte[] materialColorFragmentShaderBytes,
			byte[] materialColorVertexShaderBytes) {
		this.vertexColorFragmentShaderBytes = vertexColorFragmentShaderBytes;
		this.vertexColorVertexShaderBytes = vertexColorVertexShaderBytes;
		this.materialColorFragmentShaderBytes = materialColorFragmentShaderBytes;
		this.materialColorVertexShaderBytes = materialColorVertexShaderBytes;
	}

	@Override
	protected boolean write(OutputStream outputStream,
			ProgressReporter progressReporter) throws SerializerException {
		
		String systemDir = System.getProperty("java.io.tmpdir");
		LOGGER.info("System dir "+systemDir);
		
		//metadata
		if(Files.exists(Paths.get(systemDir+"/"+"metadata.gltfOPT"))){
				controlMode = ControlMode.METADATA;
				//String content = new String(Files.readAllBytes(Paths.get(systemDir+"/"+"metadata.gltfOPT")));
				try {
					Files.delete(Paths.get(systemDir+"/"+"metadata.gltfOPT"));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
		else if(Files.exists(Paths.get(systemDir+"/"+"full.gltfOPT"))){
				controlMode = ControlMode.FULL;				
				try {
					Files.delete(Paths.get(systemDir+"/"+"full.gltfOPT"));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
		else if(Files.exists(Paths.get(systemDir+"/"+"part.gltfOPT"))){
			try {
				controlMode = ControlMode.PART;
				String content = new String(Files.readAllBytes(Paths.get(systemDir+"/"+"part.gltfOPT")));
				String[] ids  = content.split(",");
				partIDs = Arrays.asList(ids);
				try {
					Files.delete(Paths.get(systemDir+"/"+"part.gltfOPT"));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}else {

			controlMode = ControlMode.FULL;
		}
		
		LOGGER.info("Control mode "+ controlMode.toString());
		
		
		
		gltfNode = OBJECT_MAPPER.createObjectNode();

		buffers = OBJECT_MAPPER.createArrayNode();
		meshes = OBJECT_MAPPER.createArrayNode();
		buffersViews = OBJECT_MAPPER.createArrayNode();
		scenesNode = OBJECT_MAPPER.createArrayNode();
		accessors = OBJECT_MAPPER.createArrayNode();
		nodes = OBJECT_MAPPER.createArrayNode();
		materials = OBJECT_MAPPER.createArrayNode();
		shaders = OBJECT_MAPPER.createObjectNode();

		gltfNode.set("meshes", meshes);
		gltfNode.set("bufferViews", buffersViews);
		gltfNode.set("scenes", scenesNode);
		gltfNode.set("accessors", accessors);
		gltfNode.set("nodes", nodes);
		gltfNode.set("buffers", buffers);
		gltfNode.set("materials", materials);
		// gltfNode.set("shaders", shaders);

		createVertexColorMaterial();

		try {
			LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(
					outputStream);

			generateSceneAndBody();

			// StringWriter stringWriter = new StringWriter();
			// OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(stringWriter,
			// gltfNode);
			// System.out.println(stringWriter);

			byte[] sceneBytes = gltfNode.toString().getBytes(Charsets.UTF_8);
			writeHeader(dataOutputStream, 12, 8
					+ sceneBytes.length
					+ (sceneBytes.length % 4 == 0 ? 0
							: 4 - sceneBytes.length % 4), 8 + body.capacity()
					+ (body.capacity() % 4 == 4 ? 0 : 4 - body.capacity() % 4));
			writeScene(dataOutputStream, sceneBytes);
			writeBody(dataOutputStream, body.array());
			dataOutputStream.flush();
		} catch (IOException e) {
			throw new SerializerException(e);
		}
		return false;
	}

	private void createModelNode() {
		ObjectNode translationNode = OBJECT_MAPPER.createObjectNode();
		modelTranslation = OBJECT_MAPPER.createArrayNode();

		translationChildrenNode = OBJECT_MAPPER.createArrayNode();
		translationNode.set("children", translationChildrenNode);
		translationNode.set("translation", modelTranslation);
		ObjectNode rotationNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode rotation = OBJECT_MAPPER.createArrayNode();
		ArrayNode rotationChildrenNode = OBJECT_MAPPER.createArrayNode();

		nodes.add(translationNode);

		rotationChildrenNode.add(nodes.size() - 1);
		rotationNode.set("children", rotationChildrenNode);
		rotationNode.set("rotation", rotation);

		float[] quat = normalizeQuaternion(new float[] { 1, 0, 0, -1f });

		rotation.add(quat[0]);
		rotation.add(quat[1]);
		rotation.add(quat[2]);
		rotation.add(quat[3]);

		nodes.add(rotationNode);
		defaultSceneNodes.add(nodes.size() - 1);
	}

	public float len2(float[] input) {
		return input[0] * input[0] + input[1] * input[1] + input[2] * input[2]
				+ input[3] * input[3];
	}

	private float[] normalizeQuaternion(float[] input) {
		float len = len2(input);
		if (len != 0.f && len != 1f) {
			len = (float) Math.sqrt(len);
			input[0] /= len;
			input[1] /= len;
			input[2] /= len;
			input[3] /= len;
		}
		return input;
	}

	final Logger LOGGER = LoggerFactory.getLogger(BinaryGltfSerializer2.class);
	private int state = 0;
	private void addGeometry(IfcProduct ifcProduct, ArrayNode parentNode)
			throws SerializerException {
		if(state == 0){
			listProduct.add(ifcProduct);
			return;
		}
//		String key = ifcProduct.eClass().getName();
//		if (!typedProductDebug.containsKey(key)) {
//			typedProductDebug.put(key, 0);
//		}
//		typedProductDebug.replace(key,
//				typedProductDebug.get(key) + 1);
		
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
				&& geometryInfo != null
				&& geometryInfo.getData().getVertices().length > 0) {
			int startPositionIndices = newIndicesBuffer.position();
			int startPositionVertices = newVerticesBuffer.position();
			int startPositionNormals = newNormalsBuffer.position();
			int startPositionColors = newColorsBuffer.position();

			GeometryData data = geometryInfo.getData();

			ByteBuffer indicesBuffer = ByteBuffer.wrap(data.getIndices());
			indicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
			IntBuffer indicesIntBuffer = indicesBuffer.asIntBuffer();

			ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices());
			verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
			FloatBuffer verticesFloatBuffer = verticesBuffer.asFloatBuffer();

			ByteBuffer normalsBuffer = ByteBuffer.wrap(data.getNormals());
			normalsBuffer.order(ByteOrder.LITTLE_ENDIAN);
			FloatBuffer normalsFloatBuffer = normalsBuffer.asFloatBuffer();

			FloatBuffer materialsFloatBuffer = null;
			if (data.getMaterials() != null) {
				ByteBuffer materialsBuffer = ByteBuffer.wrap(data
						.getMaterials());
				materialsBuffer.order(ByteOrder.LITTLE_ENDIAN);
				materialsFloatBuffer = materialsBuffer.asFloatBuffer();
			}

			if (data.getIndices().length > 4 * maxIndexValues) {
				int totalNrIndices = indicesIntBuffer.capacity();
				int nrParts = (totalNrIndices + maxIndexValues - 1)
						/ maxIndexValues;

				ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();

				for (int part = 0; part < nrParts; part++) {
					startPositionIndices = newIndicesBuffer.position();
					startPositionVertices = newVerticesBuffer.position();
					startPositionNormals = newNormalsBuffer.position();
					startPositionColors = newColorsBuffer.position();

					short indexCounter = 0;
					int upto = Math.min((part + 1) * maxIndexValues,
							totalNrIndices);
					for (int i = part * maxIndexValues; i < upto; i++) {
						newIndicesBuffer.putShort(indexCounter++);
					}

					int[] min = new int[] { 0 };
					int[] max = new int[] { upto };

					int nrVertices = upto - part * maxIndexValues;

					for (int i = part * maxIndexValues; i < upto; i += 3) {
						int oldIndex1 = indicesIntBuffer.get(i);
						int oldIndex2 = indicesIntBuffer.get(i + 1);
						int oldIndex3 = indicesIntBuffer.get(i + 2);

						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex1 * 3));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex1 * 3 + 1));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex1 * 3 + 2));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex2 * 3));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex2 * 3 + 1));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex2 * 3 + 2));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex3 * 3));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex3 * 3 + 1));
						newVerticesBuffer.putFloat(verticesFloatBuffer
								.get(oldIndex3 * 3 + 2));
					}
					for (int i = part * maxIndexValues; i < upto; i += 3) {
						int oldIndex1 = indicesIntBuffer.get(i);
						int oldIndex2 = indicesIntBuffer.get(i + 1);
						int oldIndex3 = indicesIntBuffer.get(i + 2);

						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex1 * 3));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex1 * 3 + 1));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex1 * 3 + 2));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex2 * 3));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex2 * 3 + 1));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex2 * 3 + 2));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex3 * 3));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex3 * 3 + 1));
						newNormalsBuffer.putFloat(normalsFloatBuffer
								.get(oldIndex3 * 3 + 2));
					}
					if (materialsFloatBuffer != null) {
						for (int i = part * maxIndexValues; i < upto; i += 3) {
							int oldIndex1 = indicesIntBuffer.get(i);
							int oldIndex2 = indicesIntBuffer.get(i + 1);
							int oldIndex3 = indicesIntBuffer.get(i + 2);

							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex1 * 4));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex1 * 4 + 1));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex1 * 4 + 2));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex1 * 4 + 3));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex2 * 4));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex2 * 4 + 1));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex2 * 4 + 2));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex2 * 4 + 3));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex3 * 4));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex3 * 4 + 1));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex3 * 4 + 2));
							newColorsBuffer.putFloat(materialsFloatBuffer
									.get(oldIndex3 * 4 + 3));
						}
					}

					ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();

					int indicesAccessor = addIndicesAccessor(ifcProduct,
							indicesBufferView, startPositionIndices,
							nrVertices / 3, min, max);
					int verticesAccessor = addVerticesAccessor(ifcProduct,
							verticesBufferView, startPositionVertices,
							nrVertices);
					int normalsAccessor = addNormalsAccessor(ifcProduct,
							normalsBufferView, startPositionNormals, nrVertices);
					int colorAccessor = -1;
					if (data.getMaterials() != null) {
						if (colorsBufferView == -1) {
							colorsBufferView = createBufferView(
									totalColorsByteLength,
									totalIndicesByteLength
											+ totalVerticesByteLength
											+ totalNormalsByteLength,
									ARRAY_BUFFER, 16);
						}
						colorAccessor = addColorsAccessor(ifcProduct,
								colorsBufferView, startPositionColors, 16);
					}
					primitivesNode.add(primitiveNode);

					primitiveNode.put("indices", indicesAccessor);
					primitiveNode.put("mode", TRIANGLES);
					ObjectNode attributesNode = OBJECT_MAPPER
							.createObjectNode();
					primitiveNode.set("attributes", attributesNode);
					attributesNode.put("NORMAL", normalsAccessor);
					attributesNode.put("POSITION", verticesAccessor);
					if (colorAccessor != -1) {
						// attributesNode.put("COLOR_0", colorAccessor);
						primitiveNode.put("material", vertexColorIndex);
					} else {
						primitiveNode.put(
								"material",
								createOrGetMaterial(ifcProduct.eClass()
										.getName(), IfcColors
										.getDefaultColor(ifcProduct.eClass()
												.getName())));
					}
				}

				int meshId = addMesh(ifcProduct, primitivesNode);
				int nodeId = addNode(meshId, ifcProduct);
				parentNode.add(nodeId);
				// LOGGER.info("geometry name :"+ifcProduct.getName());
			} else {
				int maxVal = 0;
				for (int i = 0; i < indicesIntBuffer.capacity(); i++) {
					int index = indicesIntBuffer.get(i);
					if (index > Short.MAX_VALUE) {
						throw new SerializerException(
								"Index too large to store as short " + index);
					}
					newIndicesBuffer.putShort((short) (index));
					if (index > maxVal) {
						maxVal = index;
					}
				}

				int[] min = new int[] { 0 };
				int[] max = new int[] { maxVal };

				newVerticesBuffer.put(data.getVertices());
				newNormalsBuffer.put(data.getNormals());
				if (data.getMaterials() != null) {
					newColorsBuffer.put(data.getMaterials());
				}

				int totalNrIndices = indicesIntBuffer.capacity();

				ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();

				ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();

				int indicesAccessor = addIndicesAccessor(ifcProduct,
						indicesBufferView, startPositionIndices,
						totalNrIndices, min, max);
				int verticesAccessor = addVerticesAccessor(ifcProduct,
						verticesBufferView, startPositionVertices,
						data.getVertices().length / 12);
				int normalsAccessor = addNormalsAccessor(ifcProduct,
						normalsBufferView, startPositionNormals,
						data.getNormals().length / 12);
				int colorAccessor = -1;
				if (data.getMaterials() != null) {
					if (colorsBufferView == -1) {
						colorsBufferView = createBufferView(
								totalColorsByteLength, totalIndicesByteLength
										+ totalVerticesByteLength
										+ totalNormalsByteLength, ARRAY_BUFFER,
								16);
					}
					colorAccessor = addColorsAccessor(ifcProduct,
							colorsBufferView, startPositionColors,
							data.getVertices().length / 12);
				}
				primitivesNode.add(primitiveNode);

				primitiveNode.put("indices", indicesAccessor);
				primitiveNode.put("mode", TRIANGLES);
				ObjectNode attributesNode = OBJECT_MAPPER.createObjectNode();
				primitiveNode.set("attributes", attributesNode);
				attributesNode.put("NORMAL", normalsAccessor);
				attributesNode.put("POSITION", verticesAccessor);
				if (colorAccessor != -1) {
					attributesNode.put("COLOR_0", colorAccessor);
					primitiveNode.put("material", vertexColorIndex);
				} else {
					primitiveNode.put(
							"material",
							createOrGetMaterial(ifcProduct.eClass().getName(),
									IfcColors.getDefaultColor(ifcProduct
											.eClass().getName())));
				}

				int meshId = addMesh(ifcProduct, primitivesNode);
				int nodeId = addNode(meshId, ifcProduct);

				parentNode.add(nodeId);

				// LOGGER.info("geometry(IfcOpeningElement) name :"+ifcProduct.getName());
			}
		}

	}

	ArrayNode addGroup(String name, ArrayNode parent) {
		if(state == 0)return null;
		ObjectNode parentNode = OBJECT_MAPPER.createObjectNode();
		ArrayNode childs = OBJECT_MAPPER.createArrayNode();
		// childs.add(nodeId);
		parentNode.set("children", childs);
		parentNode.put("name", name == null ? "Unknown" : name);
		// if(parent != null){
		// parent.add(parentNode);
		// }else
		nodes.add(parentNode);
		parent.add(nodes.size() - 1);
		return childs;
	}

	int indicesBufferView;
	int verticesBufferView;
	int normalsBufferView;
	int colorsBufferView = -1;
	int maxIndexValues = 16389;
	int totalColorsByteLength = 0;
	int totalBodyByteLength = 0;
	int totalIndicesByteLength = 0;
	int totalVerticesByteLength = 0;
	int totalNormalsByteLength = 0;
	ByteBuffer newIndicesBuffer, newVerticesBuffer, newNormalsBuffer,
			newColorsBuffer;

	EList<IfcObjectDefinition> getChild(IfcSpatialStructureElement group) {
		EList<IfcObjectDefinition> allChild = new BasicEList<IfcObjectDefinition>();
		EList<IfcRelDecomposes> listbuilding = group.getIsDecomposedBy();
		// LOGGER.info("getIsDecomposedBy().size():"+listbuilding.size());
		for (IfcRelDecomposes buildingstoryC : listbuilding) {
			// ArrayNode buildingChild = addGroup(building.getName());
			EList<IfcObjectDefinition> buildingstoryList = buildingstoryC
					.getRelatedObjects();
			allChild.addAll(buildingstoryList);
		}
		
		return allChild;
	}

	EList<IfcProduct> getChildProduct(IfcSpatialStructureElement group) {
		
		EList<IfcRelContainedInSpatialStructure> productsC = group
				.getContainsElements();
		// /LOGGER.info("productC.size():"+productsC.size());
		EList<IfcProduct> productAll = new BasicEList<IfcProduct>();
		for (IfcRelContainedInSpatialStructure product : productsC) {
			EList<IfcProduct> products = product.getRelatedElements();
			productAll.addAll(products);
		}	
		
		return productAll;
	}

	private int calculateBuffer(IfcProduct ifcProduct) {
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		int size = 0;
		if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
				&& geometryInfo != null
				&& geometryInfo.getData().getVertices().length > 0) {
			GeometryData data = geometryInfo.getData();
			int nrIndicesBytes = data.getIndices().length;
			size = nrIndicesBytes / 2;
			totalIndicesByteLength += nrIndicesBytes / 2;
			if (nrIndicesBytes > 4 * maxIndexValues) {
				int nrIndices = nrIndicesBytes / 4;
				totalVerticesByteLength += nrIndices * 3 * 4;
				totalNormalsByteLength += nrIndices * 3 * 4;
				if (data.getMaterials() != null) {
					totalColorsByteLength += nrIndices * 4 * 4;
				}
			} else {
				totalVerticesByteLength += data.getVertices().length;
				totalNormalsByteLength += data.getNormals().length;
				if (data.getMaterials() != null) {
					totalColorsByteLength += data.getMaterials().length;
				}
			}
		}
		return size;
	}

	private void updateMatrix(IfcProduct ifcProduct) {
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
				&& geometryInfo != null) {
			ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct
					.getGeometry().getTransformation());
			matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
			DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
			float[] matrix = new float[16];
			for (int i = 0; i < doubleBuffer.capacity(); i++) {
				matrix[i] = (float) doubleBuffer.get();
			}

			updateExtends(geometryInfo, matrix);
		}
	}
//
	List<IfcProduct> listProduct = new ArrayList<IfcProduct>();
	void doSomethingWith(IfcSpatialStructureElement spatialStructure){
		  for(IfcRelContainedInSpatialStructure containment: spatialStructure.getContainsElements()){
		    for(IfcProduct product : containment.getRelatedElements()){
		      // do something with your product, e.g. fire detector
		    	//LOGGER.info("Child "+product.getName());
		    	listProduct.add(product);
		    }
		  }
		}
	
	void traverseSpatialStructure(IfcSpatialStructureElement parent){
		  for (IfcRelDecomposes aggregation: parent.getIsDecomposedBy()){
		    for (IfcObjectDefinition child: aggregation.getRelatedObjects()){
		      if(child instanceof IfcSpatialStructureElement){
		      doSomethingWith((IfcSpatialStructureElement) child);
		      traverseSpatialStructure((IfcSpatialStructureElement) child);
		      }
		    }
		  }
		}

	private void getChildRecursive(IfcObjectDefinition product,
			ArrayNode parent, HashMap<String, ArrayNode> typedProduct) {
		if (product == null)
			return;

		getProduct(product, parent, typedProduct);
		EList<IfcObjectDefinition> childs = getChild((IfcSpatialStructureElement) product);
		if (childs != null && childs.size() > 0) {
			for (IfcObjectDefinition child : childs) {
				//ArrayNode subGroup = addGroup(child.getName(), parent);
				String type = child.eClass().getName()+" Group";
				if (!typedProduct.containsKey(type)) {
					ArrayNode productGroup = addGroup(type, parent);
					typedProduct.put(type, productGroup);
					//LOGGER.info("type:"+type);
				}
				try {
					// LOGGER.info("Hidden name:"+((IfcProduct)child).getName());
					addGeometry((IfcProduct) child, typedProduct.get(type));
				} catch (SerializerException e) {
					// TODO Auto-generated catch block
					LOGGER.info("getChildRecursive "+e.getMessage());
				}

				getChildRecursive(child, parent, typedProduct);
			}
		} else {
//			String type = product.eClass().getName()+" Group";
//			if (!typedProduct.containsKey(type)) {
//				ArrayNode productGroup = addGroup(type, parent);
//				typedProduct.put(type, productGroup);
//				// LOGGER.info("type:"+type);
//			}
		}


	}

	private void getProduct(IfcObjectDefinition product, ArrayNode parent,
			HashMap<String, ArrayNode> typedProduct) {
		EList<IfcProduct> ifcProducts = getChildProduct((IfcSpatialStructureElement) product);
		if (ifcProducts != null) {
			for (IfcProduct ifcProduct : ifcProducts) {
				String type = ifcProduct.eClass().getName()+" Group";
				if (!typedProduct.containsKey(type)) {
					ArrayNode productGroup = addGroup(type, parent);
					typedProduct.put(type, productGroup);
					LOGGER.info("type:"+type);
				}
				try {
					addGeometry(ifcProduct, typedProduct.get(type));
				} catch (SerializerException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private void buildGeometry() throws SerializerException {
		//translationChildrenNode.removeAll();
		List<IfcSite> sites = model
				.getAllWithSubTypes(org.bimserver.models.ifc2x3tc1.IfcSite.class);

		
		for (IfcSite site : sites) {
			LOGGER.info("sites.getName():" + site.getName());
			ArrayNode siteChild = addGroup(site.getName(),
					translationChildrenNode);
			addGeometry((IfcProduct)site, siteChild);
			// get site geometry
			
			HashMap<String, ArrayNode> typedProductSite = new HashMap<>();
			getProduct(site, siteChild, typedProductSite);
			//traverseSpatialStructure(site);
			EList<IfcObjectDefinition> buildings = getChild((IfcSpatialStructureElement) site);
			if (buildings != null) {
				for (IfcObjectDefinition building : buildings) {
					LOGGER.info("building.getName():" + building.getName());

					ArrayNode buildingChild = addGroup(building.getName(),
							siteChild);
					EList<IfcObjectDefinition> buildingStoreys = getChild((IfcSpatialStructureElement) building);
					if (buildingStoreys != null)
						for (IfcObjectDefinition buildingStorey : buildingStoreys) {
							LOGGER.info("buildingStorey.getName():"
									+ buildingStorey.getName());
							ArrayNode buildingStoryChild = addGroup(
									buildingStorey.getName(), buildingChild);
							HashMap<String, ArrayNode> typedProduct = new HashMap<>();
							// this. recursive
							getChildRecursive(buildingStorey,
									buildingStoryChild, typedProduct);

						}
				}
			}

		}
//
//		LOGGER.info("Refer - Result");
//		for (String key : typedProductFromModel.keySet()) {
//			LOGGER.info(key + ": " + typedProductFromModel.get(key));
//			LOGGER.info(key + ": " + typedProductDebug.get(key));
//		}
//		LOGGER.info("Result - Refer");
//		for (String key : typedProductDebug.keySet()) {
//			LOGGER.info(key + ": " + typedProductDebug.get(key));
//			LOGGER.info(key + ": " + typedProductFromModel.get(key));
//		}
	}
	HashMap<String, Integer> typedProductFromModel = new HashMap();
	HashMap<String, Integer> typedProductDebug = new HashMap();
	private void generateSceneAndBody() throws SerializerException {
		typedProductFromModel.clear();
		typedProductDebug.clear();
		totalColorsByteLength = 0;
		totalBodyByteLength = 0;
		totalIndicesByteLength = 0;
		totalVerticesByteLength = 0;
		totalNormalsByteLength = 0;
		colorsBufferView = -1;

		LOGGER.info("generteSceneAndBody");
		listProduct.clear();
		state = 0;
		buildGeometry();
		List<IfcProduct> products = listProduct;//model.getAllWithSubTypes(IfcProduct.class);
		for (IfcProduct ifcProduct : products) {
			String type = ifcProduct.eClass().getName();
			int size = calculateBuffer(ifcProduct);
			if (size > 0) {
				if (!typedProductFromModel.containsKey(type)) {
					typedProductFromModel.put(type, 0);
				}
				typedProductFromModel.replace(type, typedProductFromModel.get(type) + 1);
			}
		}

		totalBodyByteLength = totalIndicesByteLength + totalVerticesByteLength
				+ totalNormalsByteLength + totalColorsByteLength;

		body = ByteBuffer.allocate(totalBodyByteLength
				+ materialColorFragmentShaderBytes.length
				+ materialColorVertexShaderBytes.length
				+ vertexColorFragmentShaderBytes.length
				+ vertexColorVertexShaderBytes.length);
		body.order(ByteOrder.LITTLE_ENDIAN);

		newIndicesBuffer = ByteBuffer.allocate(totalIndicesByteLength);
		newIndicesBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newVerticesBuffer = ByteBuffer.allocate(totalVerticesByteLength);
		newVerticesBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newNormalsBuffer = ByteBuffer.allocate(totalNormalsByteLength);
		newNormalsBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newColorsBuffer = ByteBuffer.allocate(totalColorsByteLength);
		newColorsBuffer.order(ByteOrder.LITTLE_ENDIAN);

		indicesBufferView = createBufferView(totalIndicesByteLength, 0,
				ELEMENT_ARRAY_BUFFER, -1);
		verticesBufferView = createBufferView(totalVerticesByteLength,
				totalIndicesByteLength, ARRAY_BUFFER, 12);
		normalsBufferView = createBufferView(totalNormalsByteLength,
				totalIndicesByteLength + totalVerticesByteLength, ARRAY_BUFFER,
				12);
		colorsBufferView = -1;

		scenesNode.add(createDefaultScene());
		gltfNode.put("scene", 0);
		createModelNode();

		for (IfcProduct ifcProduct : products) {
			updateMatrix(ifcProduct);
		}

		float[] offsets = getOffsets();

		// This will "normalize" the model by moving it's axis-aligned bounding
		// box center to the 0-point. This will always be the wrong position,
		// but at least the building will be close to the 0-point
		modelTranslation.add(-offsets[0]);
		modelTranslation.add(-offsets[1]);
		modelTranslation.add(-offsets[2]);

		// for (IfcProduct ifcProduct :
		// model.getAllWithSubTypes(IfcProduct.class)) {
		// addGeometry(ifcProduct,translationChildrenNode);
		// }

		// analysis tree
		state = 1;
		buildGeometry();
		if (newIndicesBuffer.position() != newIndicesBuffer.capacity()) {
			LOGGER.info("newIndicesBuffer position:"
					+ newIndicesBuffer.position());
			LOGGER.info("newIndicesBuffer capacity:"
					+ newIndicesBuffer.capacity());
			throw new SerializerException("Not all space used");
		}
		if (newVerticesBuffer.position() != newVerticesBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newNormalsBuffer.position() != newNormalsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newColorsBuffer.position() != newColorsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}

		newIndicesBuffer.position(0);
		newVerticesBuffer.position(0);
		newNormalsBuffer.position(0);
		newColorsBuffer.position(0);

		body.put(newIndicesBuffer);
		body.put(newVerticesBuffer);
		body.put(newNormalsBuffer);
		body.put(newColorsBuffer);

		int vertexColorFragmentShaderBufferViewName = createBufferView(
				vertexColorFragmentShaderBytes.length, body.position(), -1, -1);
		body.put(vertexColorFragmentShaderBytes);

		int vertexColorVertexShaderBufferViewName = createBufferView(
				vertexColorVertexShaderBytes.length, body.position(), -1, -1);
		body.put(vertexColorVertexShaderBytes);

		int materialColorFragmentShaderBufferViewName = createBufferView(
				materialColorFragmentShaderBytes.length, body.position(), -1,
				-1);
		body.put(materialColorFragmentShaderBytes);

		int materialColorVertexShaderBufferViewName = createBufferView(
				materialColorVertexShaderBytes.length, body.position(), -1, -1);
		body.put(materialColorVertexShaderBytes);

		// gltfNode.set("animations", createAnimations());
		gltfNode.set("asset", createAsset());
		// gltfNode.set("programs", createPrograms());
		gltfNode.put("scene", 0);
		// gltfNode.set("skins", createSkins());
		// gltfNode.set("techniques", createTechniques());

		// createVertexColorShaders(vertexColorFragmentShaderBufferViewName,
		// vertexColorVertexShaderBufferViewName);
		// createMaterialColorShaders(materialColorFragmentShaderBufferViewName,
		// materialColorVertexShaderBufferViewName);

		addBuffer(body.capacity());

		// ArrayNode extensions = OBJECT_MAPPER.createArrayNode();
		// extensions.add("KHR_binary_glTF");
		// gltfNode.set("extensionsUsed", extensions);
	}

	private float[] getOffsets() {
		float[] changes = new float[3];
		for (int i = 0; i < 3; i++) {
			changes[i] = (max[i] - min[i]) / 2.0f + min[i];
		}
		return changes;
	}

	private void updateExtends(GeometryInfo geometryInfo, float[] matrix) {
		ByteBuffer verticesByteBufferBuffer = ByteBuffer.wrap(geometryInfo
				.getData().getVertices());
		verticesByteBufferBuffer.order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer floatBuffer = verticesByteBufferBuffer.asFloatBuffer();
		for (int i = 0; i < floatBuffer.capacity(); i += 3) {
			float[] input = new float[] { floatBuffer.get(i),
					floatBuffer.get(i + 1), floatBuffer.get(i + 2), 1 };
			float[] output = new float[4];
			Matrix.multiplyMV(output, 0, matrix, 0, input, 0);
			for (int j = 0; j < 3; j++) {
				float value = output[j];
				if (value > max[j]) {
					max[j] = value;
				}
				if (value < min[j]) {
					min[j] = value;
				}
			}
		}
	}

	private int addNode(int meshId, IfcProduct ifcProduct) {
		ObjectNode nodeNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode matrixArray = OBJECT_MAPPER.createArrayNode();
		ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry()
				.getTransformation());
		matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
		double[] buffer = new double[16];
		for (int i = 0; i < 16; i++) {
			double d = doubleBuffer.get(i);
			matrixArray.add(d);
			buffer[i] = d;
		}

		// test parent node
		nodeNode.put("mesh", meshId);
		if (!Matrix.isIdentity(buffer)) {
			nodeNode.set("matrix", matrixArray);
		}
		nodes.add(nodeNode);
		return nodes.size() - 1;
	}

	private ObjectNode createDefaultScene() {
		ObjectNode sceneNode = OBJECT_MAPPER.createObjectNode();

		defaultSceneNodes = OBJECT_MAPPER.createArrayNode();

		sceneNode.set("nodes", defaultSceneNodes);

		return sceneNode;
	}

	// private int createBufferView(int byteLength, int byteOffset, int
	// byteStride) {
	// return createBufferView(byteLength, byteOffset, -1, byteStride);
	// }

	private int createBufferView(int byteLength, int byteOffset, int target,
			int byteStride) {
		ObjectNode bufferViewNode = OBJECT_MAPPER.createObjectNode();

		bufferViewNode.put("buffer", 0);
		bufferViewNode.put("byteLength", byteLength);
		bufferViewNode.put("byteOffset", byteOffset);
		if (byteStride != -1) {
			bufferViewNode.put("byteStride", byteStride);
		}
		if (target != -1) {
			bufferViewNode.put("target", target);
		}

		buffersViews.add(bufferViewNode);

		return buffersViews.size() - 1;
	}

	private int addNormalsAccessor(IfcProduct ifcProduct, int bufferViewIndex,
			int byteOffset, int count) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", byteOffset);
		// accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		ArrayNode min = OBJECT_MAPPER.createArrayNode();
		min.add(-1d);
		min.add(-1d);
		min.add(-1d);
		ArrayNode max = OBJECT_MAPPER.createArrayNode();
		max.add(1);
		max.add(1);
		max.add(1);

		// accessor.set("min", min);
		// accessor.set("max", max);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addColorsAccessor(IfcProduct ifcProduct, int bufferViewIndex,
			int byteOffset, int count) {
		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", byteOffset);
		// accessor.put("byteStride", 16);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC4");

		// ArrayNode min = OBJECT_MAPPER.createArrayNode();
		// min.add(-1d);
		// min.add(-1d);
		// min.add(-1d);
		// ArrayNode max = OBJECT_MAPPER.createArrayNode();
		// max.add(1);
		// max.add(1);
		// max.add(1);
		//
		// accessor.set("min", min);
		// accessor.set("max", max);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addVerticesAccessor(IfcProduct ifcProduct, int bufferViewIndex,
			int startPosition, int count) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}
		if (count * 12 == 9000) {
			System.out.println();
		}
		GeometryData data = ifcProduct.getGeometry().getData();
		ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices());

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", startPosition);
		// accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
		float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
		for (int i = 0; i < verticesBuffer.capacity(); i += 12) {
			for (int j = 0; j < 3; j++) {
				float val = verticesBuffer.getFloat(i + (j * 4));
				if (val > max[j]) {
					max[j] = val;
				}
				if (val < min[j]) {
					min[j] = val;
				}
			}
		}

		ArrayNode minNode = OBJECT_MAPPER.createArrayNode();
		minNode.add(min[0]);
		minNode.add(min[1]);
		minNode.add(min[2]);
		ArrayNode maxNode = OBJECT_MAPPER.createArrayNode();
		maxNode.add(max[0]);
		maxNode.add(max[1]);
		maxNode.add(max[2]);

		accessor.set("min", minNode);
		accessor.set("max", maxNode);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addIndicesAccessor(IfcProduct ifcProduct, int bufferViewIndex,
			int offsetBytes, int count, int[] min, int[] max)
			throws SerializerException {
		if (count <= 0) {
			throw new SerializerException(count + " <= 0");
		}

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", offsetBytes);
		// accessor.put("byteStride", 0);
		accessor.put("componentType", UNSIGNED_SHORT);
		accessor.put("count", count);
		accessor.put("type", "SCALAR");

		// ArrayNode minArray = OBJECT_MAPPER.createArrayNode();
		// ArrayNode maxArray = OBJECT_MAPPER.createArrayNode();
		//
		// for (int i=0; i<min.length; i++) {
		// minArray.add(min[i]);
		// maxArray.add(max[i]);
		// }
		//
		// accessor.set("min", minArray);
		// accessor.set("max", maxArray);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addMesh(IfcProduct ifcProduct, ArrayNode primitivesNode) {

		final ObjectMapper mapper = new ObjectMapper();
		ObjectNode meshNode = OBJECT_MAPPER.createObjectNode();
		meshNode.set("name",
				mapper.convertValue(ifcProduct.getName(), JsonNode.class));
		meshNode.set("primitives", primitivesNode);
		meshes.add(meshNode);
		return meshes.size() - 1;
	}

	private void addBuffer(int byteLength) {
		ObjectNode bufferNode = OBJECT_MAPPER.createObjectNode();

		bufferNode.put("byteLength", byteLength);
		// bufferNode.put("uri", "data:,");

		buffers.add(bufferNode);
	}

	private JsonNode createAnimations() {
		return OBJECT_MAPPER.createArrayNode();
	}

	private JsonNode createTechniques() {
		ObjectNode techniques = OBJECT_MAPPER.createObjectNode();

		techniques.set("vertexColorTechnique", createVertexColorTechnique());
		techniques
				.set("materialColorTechnique", createMaterialColorTechnique());

		return techniques;
	}

	private ObjectNode createMaterialColorTechnique() {
		ObjectNode technique = OBJECT_MAPPER.createObjectNode();

		ObjectNode attributes = OBJECT_MAPPER.createObjectNode();
		ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
		ObjectNode states = OBJECT_MAPPER.createObjectNode();
		ObjectNode uniforms = OBJECT_MAPPER.createObjectNode();

		attributes.put("a_normal", "normal");
		attributes.put("a_position", "position");

		ObjectNode diffuse = OBJECT_MAPPER.createObjectNode();
		diffuse.put("type", 35666);
		parameters.set("diffuse", diffuse);

		ObjectNode modelViewMatrix = OBJECT_MAPPER.createObjectNode();
		modelViewMatrix.put("semantic", "MODELVIEW");
		modelViewMatrix.put("type", 35676);
		parameters.set("modelViewMatrix", modelViewMatrix);

		ObjectNode normal = OBJECT_MAPPER.createObjectNode();
		normal.put("semantic", "NORMAL");
		normal.put("type", 35665);
		parameters.set("normal", normal);

		ObjectNode normalMatrix = OBJECT_MAPPER.createObjectNode();
		normalMatrix.put("semantic", "MODELVIEWINVERSETRANSPOSE");
		normalMatrix.put("type", 35675);
		parameters.set("normalMatrix", normalMatrix);

		ObjectNode position = OBJECT_MAPPER.createObjectNode();
		position.put("semantic", "POSITION");
		position.put("type", 35665);
		parameters.set("position", position);

		ObjectNode projectionMatrix = OBJECT_MAPPER.createObjectNode();
		projectionMatrix.put("semantic", "PROJECTION");
		projectionMatrix.put("type", 35676);
		parameters.set("projectionMatrix", projectionMatrix);

		ObjectNode shininess = OBJECT_MAPPER.createObjectNode();
		shininess.put("type", 5126);
		parameters.set("shininess", shininess);

		ObjectNode specular = OBJECT_MAPPER.createObjectNode();
		specular.put("type", 35666);
		parameters.set("specular", specular);

		technique.put("program", "materialColorProgram");

		ArrayNode statesEnable = OBJECT_MAPPER.createArrayNode();
		statesEnable.add(2929);
		statesEnable.add(2884);
		states.set("enable", statesEnable);

		uniforms.put("u_diffuse", "diffuse");
		uniforms.put("u_modelViewMatrix", "modelViewMatrix");
		uniforms.put("u_normalMatrix", "normalMatrix");
		uniforms.put("u_projectionMatrix", "projectionMatrix");
		uniforms.put("u_shininess", "shininess");
		uniforms.put("u_specular", "specular");

		technique.set("attributes", attributes);
		technique.set("parameters", parameters);
		technique.set("states", states);
		technique.set("uniforms", uniforms);

		return technique;
	}

	private ObjectNode createVertexColorTechnique() {
		ObjectNode technique = OBJECT_MAPPER.createObjectNode();

		ObjectNode attributes = OBJECT_MAPPER.createObjectNode();
		ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
		ObjectNode states = OBJECT_MAPPER.createObjectNode();
		ObjectNode uniforms = OBJECT_MAPPER.createObjectNode();

		attributes.put("a_normal", "normal");
		attributes.put("a_position", "position");
		attributes.put("a_color", "color");

		ObjectNode modelViewMatrix = OBJECT_MAPPER.createObjectNode();
		modelViewMatrix.put("semantic", "MODELVIEW");
		modelViewMatrix.put("type", 35676);
		parameters.set("modelViewMatrix", modelViewMatrix);

		ObjectNode normal = OBJECT_MAPPER.createObjectNode();
		normal.put("semantic", "NORMAL");
		normal.put("type", 35665);
		parameters.set("normal", normal);

		ObjectNode normalMatrix = OBJECT_MAPPER.createObjectNode();
		normalMatrix.put("semantic", "MODELVIEWINVERSETRANSPOSE");
		normalMatrix.put("type", 35675);
		parameters.set("normalMatrix", normalMatrix);

		ObjectNode position = OBJECT_MAPPER.createObjectNode();
		position.put("semantic", "POSITION");
		position.put("type", 35665);
		parameters.set("position", position);

		ObjectNode color = OBJECT_MAPPER.createObjectNode();
		color.put("semantic", "COLOR");
		color.put("type", FLOAT_VEC_4);
		parameters.set("color", color);

		ObjectNode projectionMatrix = OBJECT_MAPPER.createObjectNode();
		projectionMatrix.put("semantic", "PROJECTION");
		projectionMatrix.put("type", 35676);
		parameters.set("projectionMatrix", projectionMatrix);

		technique.put("program", "vertexColorProgram");

		ArrayNode statesEnable = OBJECT_MAPPER.createArrayNode();
		statesEnable.add(2929);
		statesEnable.add(2884);
		states.set("enable", statesEnable);

		uniforms.put("u_modelViewMatrix", "modelViewMatrix");
		uniforms.put("u_normalMatrix", "normalMatrix");
		uniforms.put("u_projectionMatrix", "projectionMatrix");

		technique.set("attributes", attributes);
		technique.set("parameters", parameters);
		technique.set("states", states);
		technique.set("uniforms", uniforms);

		return technique;
	}

	private JsonNode createSkins() {
		return OBJECT_MAPPER.createArrayNode();
	}

	private void createVertexColorShaders(String fragmentShaderBufferViewName,
			String vertexShaderBufferViewName) {
		ObjectNode fragmentShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode fragmentShaderBinary = OBJECT_MAPPER.createObjectNode();
		fragmentShaderExtensions.set("KHR_binary_glTF", fragmentShaderBinary);
		fragmentShaderBinary.put("bufferView", fragmentShaderBufferViewName);

		ObjectNode fragmentShader = OBJECT_MAPPER.createObjectNode();
		fragmentShader.put("type", 35632);
		fragmentShader.put("uri", "data:,");
		fragmentShader.set("extensions", fragmentShaderExtensions);

		ObjectNode vertexShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode vertexShaderBinary = OBJECT_MAPPER.createObjectNode();
		vertexShaderExtensions.set("KHR_binary_glTF", vertexShaderBinary);
		vertexShaderBinary.put("bufferView", vertexShaderBufferViewName);

		ObjectNode vertexShader = OBJECT_MAPPER.createObjectNode();
		vertexShader.put("type", 35633);
		vertexShader.put("uri", "data:,");
		vertexShader.set("extensions", vertexShaderExtensions);

		shaders.set("vertexColorFragmentShader", fragmentShader);
		shaders.set("vertexColorVertexShader", vertexShader);
	}

	private void createMaterialColorShaders(
			String fragmentShaderBufferViewName,
			String vertexShaderBufferViewName) {
		ObjectNode fragmentShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode fragmentShaderBinary = OBJECT_MAPPER.createObjectNode();
		fragmentShaderExtensions.set("KHR_binary_glTF", fragmentShaderBinary);
		fragmentShaderBinary.put("bufferView", fragmentShaderBufferViewName);

		ObjectNode fragmentShader = OBJECT_MAPPER.createObjectNode();
		fragmentShader.put("type", 35632);
		fragmentShader.put("uri", "data:,");
		fragmentShader.set("extensions", fragmentShaderExtensions);

		ObjectNode vertexShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode vertexShaderBinary = OBJECT_MAPPER.createObjectNode();
		vertexShaderExtensions.set("KHR_binary_glTF", vertexShaderBinary);
		vertexShaderBinary.put("bufferView", vertexShaderBufferViewName);

		ObjectNode vertexShader = OBJECT_MAPPER.createObjectNode();
		vertexShader.put("type", 35633);
		vertexShader.put("uri", "data:,");
		vertexShader.set("extensions", vertexShaderExtensions);

		shaders.set("materialColorFragmentShader", fragmentShader);
		shaders.set("materialColorVertexShader", vertexShader);
	}

	private ObjectNode createPrograms() {
		ObjectNode programs = OBJECT_MAPPER.createObjectNode();

		programs.set("vertexColorProgram", createVertexColorsPrograms());
		programs.set("materialColorProgram", createMaterialColorsPrograms());

		return programs;
	}

	private JsonNode createVertexColorsPrograms() {
		ObjectNode program = OBJECT_MAPPER.createObjectNode();
		ArrayNode attributes = OBJECT_MAPPER.createArrayNode();

		program.set("attributes", attributes);
		attributes.add("a_normal");
		attributes.add("a_position");
		attributes.add("a_color");

		program.put("fragmentShader", "vertexColorFragmentShader");
		program.put("vertexShader", "vertexColorVertexShader");

		return program;
	}

	private JsonNode createMaterialColorsPrograms() {
		ObjectNode program = OBJECT_MAPPER.createObjectNode();
		ArrayNode attributes = OBJECT_MAPPER.createArrayNode();

		program.set("attributes", attributes);
		attributes.add("a_normal");
		attributes.add("a_position");

		program.put("fragmentShader", "materialColorFragmentShader");
		program.put("vertexShader", "materialColorVertexShader");

		return program;
	}

	private int createOrGetMaterial(String name, float[] colors) {
		if (createdMaterials.containsKey(name)) {
			return createdMaterials.get(name);
		}
		ObjectNode material = OBJECT_MAPPER.createObjectNode();

		material.put("name", name + "Material");
		// material.put("technique", "materialColorTechnique");

		ObjectNode values = OBJECT_MAPPER.createObjectNode();

		ArrayNode diffuse = OBJECT_MAPPER.createArrayNode();
		for (int i = 0; i < 4; i++) {
			diffuse.add(colors[i]);
		}
		// diffuse.add(0.8000000119209291);
		// diffuse.add(0);
		// diffuse.add(0);
		// diffuse.add(1);

		ArrayNode specular = OBJECT_MAPPER.createArrayNode();
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);

		values.set("diffuse", diffuse);
		values.set("specular", specular);
		values.put("shininess", 256);

		// material.set("values", values);

		materials.add(material);

		createdMaterials.put(name, materials.size() - 1);

		return materials.size() - 1;
	}

	private void createVertexColorMaterial() {
		ObjectNode defaultMaterial = OBJECT_MAPPER.createObjectNode();

		// defaultMaterial.put("technique", "vertexColorTechnique");

		// ObjectNode values = OBJECT_MAPPER.createObjectNode();

		// defaultMaterial.set("values", values);

		materials.add(defaultMaterial);
		vertexColorIndex = materials.size() - 1;
	}

	private JsonNode createAsset() {
		ObjectNode asset = OBJECT_MAPPER.createObjectNode();
		asset.put("version", GLTF_VERSION);
		// TODO add the optional fields
		return asset;
	}

	private void writeHeader(LittleEndianDataOutputStream dataOutputStream,
			int headerLength, int sceneLength, int bodyLength)
			throws IOException {
		dataOutputStream.writeInt(MAGIC);
		dataOutputStream.writeInt(FORMAT_VERSION);
		dataOutputStream.writeInt(headerLength + sceneLength + bodyLength);
	}

	private void writeBody(LittleEndianDataOutputStream dataOutputStream,
			byte[] body) throws IOException {
		int rest = body.length % 4 == 0 ? 0 : 4 - (body.length % 4);
		dataOutputStream.writeInt(body.length + rest); // Align to 4-bytes
		dataOutputStream.writeInt(BINARY_CHUNK);
		dataOutputStream.write(body);
		if (rest > 0) {
			dataOutputStream.write(pad(rest, (char) 0));
		}
	}

	private byte[] pad(int length, char c) {
		byte[] result = new byte[length];
		for (int i = 0; i < length; i++) {
			result[i] = (byte) c;
		}
		return result;
	}

	private void writeScene(LittleEndianDataOutputStream dataOutputStream,
			byte[] scene) throws IOException {
		int rest = scene.length % 4 == 0 ? 0 : 4 - (scene.length % 4);
		dataOutputStream.writeInt(scene.length + rest); // Align to 4-bytes
		dataOutputStream.writeInt(JSON_CHUNK);
		dataOutputStream.write(scene);
		if (rest > 0) {
			dataOutputStream.write(pad(rest, ' '));
		}
	}
}
