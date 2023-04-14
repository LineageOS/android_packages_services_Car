/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.tool.apibuilder;

import com.android.car.tool.data.ParsedData;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class to generate API txt file.
 * Build with `m -j GenericCarApiBuilder`
 */
public final class GenerateAPI {

    private static final boolean DBG = false;
    private static final boolean USE_NEW_IMPLEMENTATION = false;

    private static final String ANDROID_BUILD_TOP = "ANDROID_BUILD_TOP";
    private static final String CAR_API_PATH =
            "/packages/services/Car/car-lib/src/android/car";
    private static final String CAR_BUILT_IN_API_PATH =
            "/packages/services/Car/car-builtin-lib/src/android/car/builtin";
    private static final String CAR_API_ANNOTATION_TEST_FILE =
            "/packages/services/Car/tests/carservice_unit_test/res/raw/car_api_classes.txt";
    private static final String CAR_BUILT_IN_ANNOTATION_TEST_FILE =
            "/packages/services/Car/tests/carservice_unit_test/res/raw/"
            + "car_built_in_api_classes.txt";
    private static final String CAR_HIDDEN_API_FILE =
            "/packages/services/Car/tests/carservice_unit_test/res/raw/"
            + "car_hidden_apis.txt";
    private static final String CAR_ADDEDINORBEFORE_API_FILE =
            "/packages/services/Car/tests/carservice_unit_test/res/raw/"
                    + "car_addedinorbefore_apis.txt";
    private static final String TAB = "    ";

    // Arguments:
    private static final String PRINT_CLASSES_ONLY = "--print-classes-only";
    private static final String UPDATE_CLASSES_FOR_TEST = "--update-classes-for-test";
    private static final String UPDATE_HIDDEN_API_FOR_TEST = "--update-hidden-api-for-test";
    private static final String PRINT_HIDDEN_API_FOR_TEST = "--print-hidden-api-for-test";
    private static final String PRINT_SHORTFORM_FULL_API_FOR_TEST =
            "--print-shortform-full-api-for-test";
    private static final String GENERATE_ADDEDINORBEFORE_API_FOR_TEST =
            "--generate-addedinorbefore-api-for-test";

    private static final String INCLUDE_CONSTRUCTORS = "--include-constructors";

    // Print Level: Describes desired print level for the tool
    // PRINT_SHORT prints only a condensed version of the APIs.
    // PRINT_HIDDEN_ONLY prints only hidden APIs.
    // PRINT_ADDEDINORBEFORE_ONLY prints only APIs containing the AddedInOrBefore annotation.
    private static final int PRINT_SHORT = 1;
    private static final int PRINT_HIDDEN_ONLY = 2;
    private static final int PRINT_ADDEDINORBEFORE_ONLY = 3;

    /**
     * Main method for generate API txt file.
     */
    public static void main(final String[] args) throws Exception {
        try {
            if (args.length == 0) {
                printHelp();
                return;
            }

            String rootDir = System.getenv(ANDROID_BUILD_TOP);
            boolean includeConstructors = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase(INCLUDE_CONSTRUCTORS)) {
                    includeConstructors = true;
                } else if (args.length > i + 1 && args[i].equalsIgnoreCase("--android-build-top")) {
                    rootDir = args[i + 1];
                }
            }

            // ANDROID_BUILD_TOP must be set.
            if (rootDir == null || rootDir.isEmpty()) {
                printHelp();
                return;
            }

            List<File> allJavaFiles_carLib = getAllFiles(new File(rootDir + CAR_API_PATH));
            List<File> allJavaFiles_carBuiltInLib = getAllFiles(
                    new File(rootDir + CAR_BUILT_IN_API_PATH));

            ParsedData parsedDataCarLib = new ParsedData();
            ParsedData parsedDataCarBuiltinLib = new ParsedData();

            if (USE_NEW_IMPLEMENTATION) {
                ParsedDataBuilder.populateParsedData(allJavaFiles_carLib, parsedDataCarLib);
                ParsedDataBuilder.populateParsedData(allJavaFiles_carBuiltInLib,
                        parsedDataCarBuiltinLib);
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(PRINT_CLASSES_ONLY)) {
                if (USE_NEW_IMPLEMENTATION) {
                    ParsedDataHelper.getClassNamesOnly(parsedDataCarLib)
                            .forEach((string) -> System.out.println(string));
                    ParsedDataHelper.getClassNamesOnly(parsedDataCarBuiltinLib)
                            .forEach((string) -> System.out.println(string));
                } else {
                    for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                        printOrUpdateAllClasses(allJavaFiles_carLib.get(i), true, null);
                    }
                    for (int i = 0; i < allJavaFiles_carBuiltInLib.size(); i++) {
                        printOrUpdateAllClasses(allJavaFiles_carBuiltInLib.get(i), true, null);
                    }
                }
                return;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(UPDATE_CLASSES_FOR_TEST)) {
                if (USE_NEW_IMPLEMENTATION) {
                    writeListToFile(rootDir + CAR_API_ANNOTATION_TEST_FILE,
                            ParsedDataHelper.getClassNamesOnly(parsedDataCarLib));
                    writeListToFile(rootDir + CAR_API_ANNOTATION_TEST_FILE,
                            ParsedDataHelper.getClassNamesOnly(parsedDataCarBuiltinLib));
                } else {
                    List<String> api_list = new ArrayList<>();
                    for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                        printOrUpdateAllClasses(allJavaFiles_carLib.get(i), false, api_list);
                    }

                    List<String> built_in_api_list = new ArrayList<>();
                    for (int i = 0; i < allJavaFiles_carBuiltInLib.size(); i++) {
                        printOrUpdateAllClasses(allJavaFiles_carBuiltInLib.get(i), false,
                                built_in_api_list);
                    }
                    writeListToFile(rootDir + CAR_BUILT_IN_ANNOTATION_TEST_FILE, built_in_api_list);
                }
                return;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(UPDATE_HIDDEN_API_FOR_TEST)) {
                if (USE_NEW_IMPLEMENTATION) {
                    if (includeConstructors) {
                        writeListToFile(rootDir + CAR_HIDDEN_API_FILE,
                                ParsedDataHelper
                                        .getHiddenApisWithHiddenConstructor(parsedDataCarLib));
                    } else {
                        writeListToFile(rootDir + CAR_HIDDEN_API_FILE,
                                ParsedDataHelper.getHiddenApisOnly(parsedDataCarLib));
                    }
                } else {
                    List<String> allCarAPIs = new ArrayList<>();
                    for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                        allCarAPIs.addAll(
                                parseJavaFile(allJavaFiles_carLib.get(i), PRINT_HIDDEN_ONLY,
                                        includeConstructors));
                    }
                    writeListToFile(rootDir + CAR_HIDDEN_API_FILE, allCarAPIs);
                }
                return;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(PRINT_HIDDEN_API_FOR_TEST)) {
                if (USE_NEW_IMPLEMENTATION) {
                    if (includeConstructors) {
                        ParsedDataHelper.getHiddenApisWithHiddenConstructor(parsedDataCarLib)
                                .forEach((string) -> System.out.println(string));
                    } else {
                        ParsedDataHelper.getHiddenApisOnly(parsedDataCarLib)
                                .forEach((string) -> System.out.println(string));
                    }
                } else {
                    List<String> allCarAPIs = new ArrayList<>();
                    for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                        allCarAPIs
                                .addAll(parseJavaFile(allJavaFiles_carLib.get(i), PRINT_HIDDEN_ONLY,
                                        includeConstructors));
                    }
                    print(allCarAPIs);
                }
                return;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(PRINT_SHORTFORM_FULL_API_FOR_TEST)) {
                if (USE_NEW_IMPLEMENTATION) {
                    if (includeConstructors) {
                        ParsedDataHelper.getAllApisWithConstructor(parsedDataCarLib)
                                .forEach((string) -> System.out.println(string));
                    } else {
                        ParsedDataHelper.getAllApis(parsedDataCarLib)
                                .forEach((string) -> System.out.println(string));
                    }
                } else {
                    List<String> allCarAPIs = new ArrayList<>();
                    for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                        allCarAPIs.addAll(
                                parseJavaFile(allJavaFiles_carLib.get(i), PRINT_SHORT,
                                        includeConstructors));
                    }
                    print(allCarAPIs);
                }
                return;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase(
                    GENERATE_ADDEDINORBEFORE_API_FOR_TEST)) {
                if (USE_NEW_IMPLEMENTATION) {
                    writeListToFile(rootDir + CAR_ADDEDINORBEFORE_API_FILE,
                            ParsedDataHelper.getAddedInOrBeforeApisOnly(parsedDataCarLib));
                } else {

                List<String> allCarAPIs = new ArrayList<>();
                for (int i = 0; i < allJavaFiles_carLib.size(); i++) {
                    allCarAPIs.addAll(
                            parseJavaFile(allJavaFiles_carLib.get(i), PRINT_ADDEDINORBEFORE_ONLY,
                                    includeConstructors));
                }
                writeListToFile(rootDir + CAR_ADDEDINORBEFORE_API_FILE, allCarAPIs);
                }
                return;
            }
        } catch (Exception e) {
            throw e;
        }
    }

    private static void print(List<String> data) {
        for (String string : data) {
            System.out.println(string);
        }
    }

    private static void writeListToFile(String filePath, List<String> data) throws IOException {
        Path file = Paths.get(filePath);
        Files.write(file, data, StandardCharsets.UTF_8);
    }

    private static void printHelp() {
        System.out.println("**** Help ****");
        System.out.println("At least one argument is required. Supported arguments - ");
        System.out.println(PRINT_CLASSES_ONLY + " : Would print the list of valid class and"
                + " interfaces.");
        System.out.println(UPDATE_CLASSES_FOR_TEST + " : Would update the test file with the list"
                + " of valid class and interfaces. These files are updated"
                + " tests/carservice_unit_test/res/raw/car_api_classes.txt and"
                + " tests/carservice_unit_test/res/raw/car_built_in_api_classes.txt");
        System.out.println(PRINT_HIDDEN_API_FOR_TEST + " : Would generate hidden api list for"
                + " testing. Results would be printed.");
        System.out.println(UPDATE_HIDDEN_API_FOR_TEST + " : Would generate hidden api list for"
                + " testing. Results would be updated in " + CAR_HIDDEN_API_FILE);
        System.out.println(
                PRINT_SHORTFORM_FULL_API_FOR_TEST + " : Prints a condensed version of all apis");
        System.out.println(GENERATE_ADDEDINORBEFORE_API_FOR_TEST
                + " : Would generate the api list that contains the @AddedInOrBefore annotation. "
                + "Results would be updated in " + CAR_ADDEDINORBEFORE_API_FILE);
        System.out.println("Second optional argument is value of Git Root Directory. By default, "
                + "it is environment variable ANDROID_BUILD_TOP. If environment variable is not set"
                + "then provide using --android-build-top <directory>");
        System.out.println("An additional optional argument " + INCLUDE_CONSTRUCTORS
                + " will include class constructors in the API output");
    }

    private static List<File> getAllFiles(File folderName) {
        List<File> allFiles = new ArrayList<>();
        File[] files = folderName.listFiles();

        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".java")) {
                if (DBG) {
                    System.out.printf("File added %s\n", files[i]);
                }
                allFiles.add(files[i]);
            }
            if (files[i].isDirectory()) {
                allFiles.addAll(getAllFiles(files[i]));
            }
        }
        // List files doesn't guarantee fixed order on all systems. It is better to sort the list.
        Collections.sort(allFiles);
        return allFiles;
    }

    private static void printOrUpdateAllClasses(File file, boolean print, List<String> updateList)
            throws Exception {
        if (!print && updateList == null) {
            throw new Exception("update list should not be null if not printing.");
        }

        CompilationUnit cu = StaticJavaParser.parse(file);
        String packageName = cu.getPackageDeclaration().get().getNameAsString();

        new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, Object arg) {
                if (!classOrInterfaceDeclaration.isPublic()
                        && !classOrInterfaceDeclaration.isProtected()) {
                    return;
                }

                String className = classOrInterfaceDeclaration.getFullyQualifiedName().get()
                        .substring(packageName.length() + 1);
                String useableClassName = packageName + "." + className.replace(".", "$");
                if (print) {
                    System.out.println(useableClassName);
                } else {
                    updateList.add(useableClassName);
                }
                super.visit(classOrInterfaceDeclaration, arg);
            }
        }.visit(cu, null);
    }

    private static List<String> parseJavaFile(File file, int printLevel,
            boolean includeConstructors)
            throws Exception {
        List<String> parsedList = new ArrayList<>();

        // Add code to parse file
        CompilationUnit cu = StaticJavaParser.parse(file);
        String packageName = cu.getPackageDeclaration().get().getNameAsString();

        new VoidVisitorAdapter<Object>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Object arg) {
                if (!n.isPublic() && !n.isProtected()) {
                    return;
                }

                String className = n.getFullyQualifiedName().get()
                        .substring(packageName.length() + 1);
                String classType = n.isInterface() ? "interface" : "class";
                boolean hiddenClass = false;

                if (!n.getJavadoc().isEmpty()) {
                    hiddenClass = n.getJavadoc().get().toText().contains("@hide");
                }

                boolean isClassSystemAPI = false;

                NodeList<AnnotationExpr> classAnnotations = n.getAnnotations();
                for (int j = 0; j < classAnnotations.size(); j++) {
                    if (classAnnotations.get(j).getName().asString().contains("SystemApi")) {
                        isClassSystemAPI = true;
                    }
                }

                String classDeclaration = classType + " "
                        + (hiddenClass && !isClassSystemAPI ? "@hiddenOnly " : "")
                        + (hiddenClass ? "@hide " : "")
                        + (isClassSystemAPI ? "@SystemApi " : "") + className + " package "
                        + packageName;

                boolean wholeClassIsHidden = hiddenClass && !isClassSystemAPI;

                if (DBG) {
                    System.out.println(classDeclaration);
                }

                List<FieldDeclaration> fields = n.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    FieldDeclaration field = fields.get(i);
                    if (n.isInterface() && field.isPrivate()) {
                        continue;
                    }
                    if (!n.isInterface() && !field.isPublic() && !field.isProtected()) {
                        continue;
                    }

                    String fieldName = field.getVariables().get(0).getName().asString();
                    String fieldType = field.getVariables().get(0).getTypeAsString();
                    boolean fieldInitialized = !field.getVariables().get(0).getInitializer()
                            .isEmpty();
                    String fieldInitializedValue = "";
                    if (fieldInitialized) {
                        fieldInitializedValue = field.getVariables().get(0).getInitializer().get()
                                .toString();
                    }

                    // special case
                    if (fieldName.equalsIgnoreCase("CREATOR")) {
                        fieldInitialized = false;
                    }

                    boolean isSystem = false;
                    boolean isHidden = false;
                    boolean hasAddedInOrBefore = false;
                    String version = "";

                    if (!field.getJavadoc().isEmpty()) {
                        isHidden = field.getJavadoc().get().toText().contains("@hide");
                    }

                    NodeList<AnnotationExpr> annotations = field.getAnnotations();
                    for (int j = 0; j < annotations.size(); j++) {
                        String annotationString = annotations.get(j).getName().asString();
                        if (annotationString.contains("SystemApi")) {
                            isSystem = true;
                        }
                        if (annotationString.contains("AddedInOrBefore")) {
                            hasAddedInOrBefore = true;
                        }
                        if (annotationString.equals("AddedIn")) {
                            String major = getVersion(annotations.get(j), "majorVersion");
                            String minor = getVersion(annotations.get(j), "minorVersion");
                            if (!major.equals("33")) {
                                System.out.println("ERROR:  major should be 33 for " + field);
                            }
                            version = "TIRAMISU_" + minor;
                        }
                        if (annotationString.equals("ApiRequirements")) {
                            String major = getVersion(annotations.get(j), "minCarVersion");
                            version = major.split("\\.")[major.split("\\.").length - 1];
                        }

                    }

                    String parsedName = packageName + " " + className + " "
                            + fieldType + " " + fieldName;

                    switch (printLevel) {
                        case PRINT_SHORT:
                            parsedList.add(parsedName);
                            break;
                        case PRINT_HIDDEN_ONLY:
                            if (wholeClassIsHidden || (isHidden && !isSystem)) {
                                parsedList.add(parsedName);
                            }
                            break;
                        case PRINT_ADDEDINORBEFORE_ONLY:
                            if (hasAddedInOrBefore) {
                                parsedList.add(packageName + "." + className + "." + fieldName);
                            }
                            break;
                        default:
                            System.err.println("Unknown print level specified: " + printLevel);
                            break;
                    }
                }

                // get all constructors
                if (includeConstructors) {
                    parseConstructors(n, wholeClassIsHidden, className);
                }

                // get all the methods
                List<MethodDeclaration> methods = n.getMethods();
                for (int i = 0; i < methods.size(); i++) {
                    MethodDeclaration method = methods.get(i);
                    if (n.isInterface() && method.isPrivate()) {
                        continue;
                    }
                    if (!n.isInterface() && !method.isPublic() && !method.isProtected()) {
                        continue;
                    }
                    String returnType = method.getTypeAsString();
                    String methodName = method.getName().asString();

                    boolean isSystem = false;
                    boolean isHidden = false;
                    boolean hasAddedInOrBefore = false;
                    String version = "";
                    if (!method.getJavadoc().isEmpty()) {
                        isHidden = method.getJavadoc().get().toText().contains("@hide");
                    }

                    NodeList<AnnotationExpr> annotations = method.getAnnotations();
                    for (int j = 0; j < annotations.size(); j++) {
                        String annotationString = annotations.get(j).getName().asString();
                        if (annotationString.contains("SystemApi")) {
                            isSystem = true;
                        }
                        if (annotationString.contains("AddedInOrBefore")) {
                            hasAddedInOrBefore = true;
                        }
                        if (annotationString.equals("AddedIn")) {
                            String major = getVersion(annotations.get(j), "majorVersion");
                            String minor = getVersion(annotations.get(j), "minorVersion");
                            if (!major.equals("33")) {
                                System.out.println("ERROR:  major should be 33 for " + method);
                            }
                            version = "TIRAMISU_" + minor;
                        }
                        if (annotationString.equals("ApiRequirements")) {
                            String major = getVersion(annotations.get(j), "minCarVersion");
                            version = major.split("\\.")[major.split("\\.").length - 1];
                        }

                    }

                    StringBuilder parametersString = new StringBuilder();

                    parametersString.append("(");

                    List<Parameter> parameters = method.getParameters();
                    for (int k = 0; k < parameters.size(); k++) {
                        Parameter parameter = parameters.get(k);
                        parametersString.append(parameter.getTypeAsString());
                        parametersString.append(" ");
                        parametersString.append(parameter.getNameAsString());
                        if (k < parameters.size() - 1) {
                            parametersString.append(", ");
                        }
                    }
                    parametersString.append(")");

                    String parsedName = packageName + " " + className + " "
                            + returnType + " " + methodName + parametersString;

                    switch (printLevel) {
                        case PRINT_SHORT:
                            parsedList.add(parsedName);
                            break;
                        case PRINT_HIDDEN_ONLY:
                            if (wholeClassIsHidden || (isHidden && !isSystem)) {
                                parsedList.add(parsedName);
                            }
                            break;
                        case PRINT_ADDEDINORBEFORE_ONLY:
                            if (hasAddedInOrBefore) {
                                parsedList.add(packageName + "." + className + "." + methodName);
                            }
                            break;
                        default:
                            System.err.println("Unknown print level specified: " + printLevel);
                            break;
                    }
                }

                super.visit(n, arg);
            }

            private String getVersion(AnnotationExpr annotationExpr, String parameterName) {
                List<MemberValuePair> children = annotationExpr
                        .getChildNodesByType(MemberValuePair.class);
                for (MemberValuePair memberValuePair : children) {
                    if (parameterName.equals(memberValuePair.getNameAsString())) {
                        if (memberValuePair.getValue() == null) {
                            return "0";
                        }
                        return memberValuePair.getValue().toString();
                    }
                }
                return "0";
            }

            private void parseConstructors(ClassOrInterfaceDeclaration n,
                    boolean wholeClassIsHidden, String className) {
                List<ConstructorDeclaration> constructors = n.getConstructors();
                for (ConstructorDeclaration constructor : constructors) {
                    if (!n.isInterface() && !constructor.isPublic() && !constructor.isProtected()) {
                        continue;
                    }

                    String constructorName = constructor.getName().asString();

                    boolean isSystem = false;
                    boolean isHidden = false;
                    boolean hasAddedInOrBefore = false;

                    String version = "";
                    if (!constructor.getJavadoc().isEmpty()) {
                        isHidden = constructor.getJavadoc().get().toText().contains("@hide");
                    }

                    NodeList<AnnotationExpr> annotations = constructor.getAnnotations();
                    for (int j = 0; j < annotations.size(); j++) {
                        String annotationString = annotations.get(j).getName().asString();
                        if (annotationString.contains("SystemApi")) {
                            isSystem = true;
                        }
                        if (annotationString.contains("AddedInOrBefore")) {
                            hasAddedInOrBefore = true;
                        }
                        if (annotationString.equals("AddedIn")) {
                            String major = getVersion(annotations.get(j), "majorVersion");
                            String minor = getVersion(annotations.get(j), "minorVersion");
                            if (!major.equals("33")) {
                                System.out.println("ERROR:  major should be 33 for " + constructor);
                            }
                            version = "TIRAMISU_" + minor;
                        }
                        if (annotationString.equals("ApiRequirements")) {
                            String major = getVersion(annotations.get(j), "minCarVersion");
                            version = major.split("\\.")[major.split("\\.").length - 1];
                        }

                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("method ");
                    sb.append(version + " ");
                    if (isHidden && !isSystem) {
                        sb.append("@hiddenOnly ");
                    }

                    sb.append(constructorName);
                    sb.append(" ");
                    sb.append(constructorName);

                    StringBuilder parametersString = new StringBuilder();

                    parametersString.append("(");

                    List<Parameter> parameters = constructor.getParameters();
                    for (int k = 0; k < parameters.size(); k++) {
                        Parameter parameter = parameters.get(k);
                        parametersString.append(parameter.getTypeAsString());
                        parametersString.append(" ");
                        parametersString.append(parameter.getNameAsString());
                        if (k < parameters.size() - 1) {
                            parametersString.append(", ");
                        }
                    }
                    parametersString.append(")");

                    sb.append(parametersString);

                    if (DBG) {
                        System.out.printf("%s%s\n", TAB, sb);
                    }

                    String parsedName = packageName + " " + className + " "
                            + constructorName + " " + constructorName + parametersString;

                    switch (printLevel) {
                        case PRINT_SHORT:
                            parsedList.add(parsedName);
                            break;
                        case PRINT_HIDDEN_ONLY:
                            if (wholeClassIsHidden || (isHidden && !isSystem)) {
                                parsedList.add(parsedName);
                            }
                            break;
                        case PRINT_ADDEDINORBEFORE_ONLY:
                            if (hasAddedInOrBefore) {
                                parsedList.add(
                                        packageName + "." + className + "." + constructorName);
                            }
                            break;
                        default:
                            System.err.println("Unknown print level specified: " + printLevel);
                            break;
                    }
                }
            }
        }.visit(cu, null);
        return parsedList;
    }
}
