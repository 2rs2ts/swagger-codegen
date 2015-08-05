package io.swagger.codegen.languages;


import io.swagger.codegen.*;
import io.swagger.models.ArrayModel;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Response;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.SerializableParameter;
import io.swagger.models.properties.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Generates a series of <a href="https://developers.google.com/discovery/v1/reference/apis">Google Discovery Docs</a>
 * from a Swagger specification. Models are separated into separate files from the main API definition, which is
 * merged into one file.
 *
 * Added mustache tokens:
 *
 * <ul>
 *     <li><code>models.model.filename</code></li>
 *     <li><code>operations.operation.returnFormat</code></li>
 * </ul>
 */
public class GDDGenerator extends DefaultCodegen implements CodegenConfig {

    public GDDGenerator() {
        super();
        templateDir = "gdd";
        outputFolder = "generated-code/gdd";
        modelTemplateFiles.put("model.mustache", ".json");
        supportingFiles.add(new SupportingFile("api.mustache", "", "api.json"));
        typeMapping.put("long", "string");  // GDD is bizarre
        typeMapping.put("float", "number");
        typeMapping.put("double", "number");
        typeMapping.put("date", "string");
        typeMapping.put("date-time", "string");
        typeMapping.put("map", "object");
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.DOCUMENTATION;
    }

    @Override
    public String getName() {
        return "gdd";
    }

    @Override
    public String getHelp() {
        return "Creates a set of Google Discovery Documents.";
    }

    // hack to make sure we don't set the type mapping for other generators... thanks SPI
    @Override
    public void processOpts() {
        super.processOpts();
        CodegenModelFactory.setTypeMapping(CodegenModelType.MODEL, GDDCodegenModel.class);
        CodegenModelFactory.setTypeMapping(CodegenModelType.OPERATION, GDDCodegenOperation.class);
        CodegenModelFactory.setTypeMapping(CodegenModelType.PARAMETER, GDDCodegenParameter.class);
        CodegenModelFactory.setTypeMapping(CodegenModelType.PROPERTY, GDDCodegenProperty.class);
    }

    @Override
    public String toApiName(String name) {
        // sanitizeTag is fucking me over
        return name.replaceAll("([a-z])([A-Z]+)", "$1-$2").toLowerCase();
    }

    @Override
    public String toVarName(String name) {
        return super.toVarName(name).replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    @Override
    public String toModelFilename(String name) {
        return name.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    private String relativeFilename(String name) {
        return "./" + toModelFilename(name) + ".json";
    }

    // add format to each property and add filename and requiredModels to model
    @Override
    public CodegenModel fromModel(String name, Model model) {
        GDDCodegenModel cm = (GDDCodegenModel) super.fromModel(name, model);
        // add filename
        cm.filename = relativeFilename(cm.name);
        // add requiredModels and add format to the properties
        cm.requiredModels = new ArrayList<CodegenModel>();
        for (CodegenProperty prop : cm.vars) {
            if ((prop.isContainer && !typeMapping.containsKey(prop.datatype)) || !prop.isPrimitiveType) {

            }
            Map<String, Property> properties = model.getProperties();
            if (null != properties) {
                String format = properties.get(prop.name).getFormat();
                if (null != format) {
                    ((GDDCodegenProperty) prop).format = format;
                }
            }
        }
        return cm;
    }

    // adds returnFormat to operations which return something with a format
    @Override
    public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Model> definitions) {
        GDDCodegenOperation co = (GDDCodegenOperation) super.fromOperation(path, httpMethod, operation, definitions);
//        Map<String, String> codegenParameterFormats = new HashMap<String, String>();
//        for (Parameter param: operation.getParameters()) {
//            if (param instanceof SerializableParameter) {
//                codegenParameterFormats.put(co.baseName, ((SerializableParameter) param).getFormat());
//            }
//        }
//        operationParamFormats.put(co, codegenParameterFormats);
        Response response = findMethodResponse(operation.getResponses());
        if (null != response) {
            Property schema = response.getSchema();
            if (null != schema) {
                co.returnFormat = schema.getFormat();
            }
        }
        return co;
    }

    // add format to parameter if param is a SerializableParameter. add isPrimitiveType if container contains primitives
    @Override
    public CodegenParameter fromParameter(Parameter param, Set<String> imports) {
        GDDCodegenParameter cp = (GDDCodegenParameter) super.fromParameter(param, imports);
        if (param instanceof SerializableParameter) {
            SerializableParameter p = (SerializableParameter) param;
            // add format
            cp.format = p.getFormat();
            // add isPrimitiveType
            Property property = null;
            if ("array".equals(p.getType())) {
                Property inner = p.getItems();
                if (null != inner) {
                    property = new ArrayProperty(inner);
                }
            } else if ("object".equals(p.getType())) {
                Property inner = p.getItems();
                if (null != inner) {
                    property = new MapProperty(inner);
                }
            } else {
                property = PropertyBuilder.build(p.getType(), p.getFormat(), null);
            }
            if (null != property) {
                CodegenProperty model = fromProperty(p.getName(), property);
                cp.isPrimitiveType = model.isPrimitiveType;
            }
        } else {
            BodyParameter bp = (BodyParameter) param;
            Model model = bp.getSchema();
            if (model instanceof ArrayModel) {
                ArrayProperty ap = new ArrayProperty().items(((ArrayModel) model).getItems());
                CodegenProperty prop = fromProperty("inner", ap);
                cp.isPrimitiveType = prop.isPrimitiveType;
            }
        }
        return cp;
    }

    // adds isReadOnly, format, filename to properties
    @Override
    public CodegenProperty fromProperty(String name, Property p) {
        GDDCodegenProperty cp = (GDDCodegenProperty) super.fromProperty(name, p);
        if (null != cp) {
            // add isReadOnly
            cp.isReadOnly = p.getReadOnly();
            // add format
            cp.format = p.getFormat();
            // add filename
            cp.filename = relativeFilename(p.getType());
        }
        return cp;
    }

//    @Override
//    @SuppressWarnings("unchecked")
//    public Map<String, Object> postProcessOperations(Map<String, Object> objs) {
//        Map<String, Object> operations = (Map<String, Object>) objs.get("operations");
//        List<Object> operationsAsMaps = new ArrayList<Object>(operations.size());
//        for (CodegenOperation operation: (List<CodegenOperation>) operations.get("operation")) {
//            Map<String, Object> operationAsMap = asMap(operation);
////            operationAsMap.put("allParams", addFormatsToParams(operation, operation.allParams));
////            operationAsMap.put("bodyParams", addFormatsToParams(operation, operation.bodyParams));
////            operationAsMap.put("pathParams", addFormatsToParams(operation, operation.pathParams));
////            operationAsMap.put("queryParams", addFormatsToParams(operation, operation.queryParams));
////            operationAsMap.put("headerParams", addFormatsToParams(operation, operation.headerParams));
////            operationAsMap.put("formParams", addFormatsToParams(operation, operation.formParams));
//            // todo is requiredParams not a thing anymore?
//            if (operationResponseFormats.containsKey(operation)) {
//                operationAsMap.put("returnFormat", operationResponseFormats.get(operation));
//            }
//            operationsAsMaps.add(operationAsMap);
//        }
//        objs.put("operations", operationsAsMaps);
//        return objs;
//    }

//    private List<Object> addFormatsToParams(CodegenOperation operation, List<CodegenParameter> params) {
//        List<Object> result = new ArrayList<Object>();
//        for (CodegenParameter cp: params) {
//            Map<String, Object> paramAsMap = asMap(cp);
//            if (operationParamFormats.get(operation).containsKey(cp.baseName)) {
//                paramAsMap.put("format", operationParamFormats.get(operation).get(cp.baseName));
//            }
//        }
//        return result;
//    }

    //Map<String, Object> postProcessSupportingFileData(Map<String, Object> objs);

    private Map<String, Object> asMap(Object o) {
        Map<String, Object> result = new HashMap<String, Object>();
        for (Field field : o.getClass().getDeclaredFields()) {
            try {
                result.put(field.getName(), field.get(o));
            } catch (Exception e) {
                System.out.println("WARNING: unable to get " + field.getName() + " from " + o);
            }
        }
        return result;
    }

    public Map<String, Object> postProcessModels(Map<String, Object> objs) {
        // add required models
        // iterate through these models or use the map approach from before?
        return objs;
    }

    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation, CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        // stop the clustering into tags
//        List<String> tags = operation.getTags();
//        if (tags == null) {
//            tags = new ArrayList<String>();
//            tags.add("default");
//        }
        super.addOperationToGroup(tag, resourcePath, operation, co, operations);
    }


    /*
     * Subclasses of Codegen classes which add fields we need
     */

    public static class GDDCodegenModel extends CodegenModel {
        public String filename;
        public List<CodegenModel> requiredModels;   // actually I just need name and filename, so maybe I should be using a map?
    }

    public static class GDDCodegenOperation extends CodegenOperation {
        public String returnFormat;
    }

    public static class GDDCodegenParameter extends CodegenParameter {
        public Boolean isPrimitiveType;
        public String format;

        @Override
        public GDDCodegenParameter copy() {
            GDDCodegenParameter output = new GDDCodegenParameter();
            output.isFile = this.isFile;
            output.notFile = this.notFile;
            output.hasMore = this.hasMore;
            output.isContainer = this.isContainer;
            output.secondaryParam = this.secondaryParam;
            output.baseName = this.baseName;
            output.paramName = this.paramName;
            output.dataType = this.dataType;
            output.collectionFormat = this.collectionFormat;
            output.description = this.description;
            output.baseType = this.baseType;
            output.isFormParam = this.isFormParam;
            output.isQueryParam = this.isQueryParam;
            output.isPathParam = this.isPathParam;
            output.isHeaderParam = this.isHeaderParam;
            output.isCookieParam = this.isCookieParam;
            output.isBodyParam = this.isBodyParam;
            output.required = this.required;
            output.jsonSchema = this.jsonSchema;
            output.defaultValue = this.defaultValue;

            // added fields
            output.format = this.format;
            output.isPrimitiveType = this.isPrimitiveType;

            return output;
        }
    }

    public static class GDDCodegenProperty extends CodegenProperty {
        public Boolean isReadOnly;
        public String format, filename;
    }

}
