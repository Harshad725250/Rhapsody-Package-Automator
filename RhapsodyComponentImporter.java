import com.telelogic.rhapsody.core.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class RhapsodyComponentImporter {

    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "^\\s*class\\s+(\\w+)(?:\\s*:\\s*(?:public|protected|private)\\s+(\\w+))?\\s*\\{?",
        Pattern.MULTILINE
    );

    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
        "^\\s*((?:const\\s+)?[\\w:<>*&]+(?:\\s*[*&])?)\\s+(\\w+)\\s*;",
        Pattern.MULTILINE
    );

    private static final Pattern OPERATION_PATTERN = Pattern.compile(
        "^\\s*((?:virtual|static|explicit|inline)\\s+)?" +
        "((?:const\\s+)?[\\w:<>*&]+(?:\\s*[*&])?)\\s+" +
        "(\\w+)\\s*\\(([^)]*)\\)\\s*(?:const)?\\s*(?:override|= 0)?\\s*;",
        Pattern.MULTILINE
    );

    private static final Pattern VISIBILITY_PATTERN = Pattern.compile(
        "^\\s*(public|private|protected)\\s*:",
        Pattern.MULTILINE
    );


    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java RhapsodyComponentImporter <headers_dir> <target_package>");
            System.exit(1);
        }

        String headersDir = args[0];
        String targetPkg  = args[1];

        System.out.println("=== Rhapsody Component Importer (v10.0.1 compatible) ===");
        System.out.println("Headers directory : " + headersDir);
        System.out.println("Target package    : " + targetPkg);
        System.out.println();

        IRPApplication app = null;
        try {
            RhapsodyAppServer appServer = new RhapsodyAppServer();
            app = appServer.getActiveRhapsodyApplication();
        } catch (Exception e) {
            System.err.println("ERROR: Could not connect to Rhapsody: " + e.getMessage());
            System.err.println("Make sure Rhapsody is running and a project is open.");
            System.exit(1);
        }

        if (app == null) {
            System.err.println("ERROR: No active Rhapsody application found.");
            System.exit(1);
        }

        IRPProject project = app.activeProject();
        if (project == null) {
            System.err.println("ERROR: No project is currently open in Rhapsody.");
            System.exit(1);
        }

        System.out.println("Connected to Rhapsody project: " + project.getName());

        IRPPackage targetPackage = findOrCreatePackage(project, targetPkg);
        if (targetPackage == null) {
            System.err.println("ERROR: Could not find or create package: " + targetPkg);
            System.exit(1);
        }

        System.out.println("Target package resolved: " + targetPackage.getName());
        System.out.println();

        List<File> headerFiles = collectHeaderFiles(headersDir);
        if (headerFiles.isEmpty()) {
            System.out.println("No header files (.h / .hpp) found in: " + headersDir);
            System.exit(0);
        }

        System.out.println("Found " + headerFiles.size() + " header file(s). Processing...");
        System.out.println();

        int totalClasses = 0, totalAttributes = 0, totalOperations = 0;

        for (File header : headerFiles) {
            System.out.println("--- Processing: " + header.getName() + " ---");
            try {
                String content = new String(Files.readAllBytes(header.toPath()));
                int[] counts = processHeaderContent(content, header.getName(), targetPackage, app);
                totalClasses    += counts[0];
                totalAttributes += counts[1];
                totalOperations += counts[2];
            } catch (IOException e) {
                System.err.println("WARNING: Could not read file: " + header.getAbsolutePath());
            }
            System.out.println();
        }

        System.out.println("Saving project...");
        project.save();

        System.out.println("=== Import Complete ===");
        System.out.println("Classes added/updated : " + totalClasses);
        System.out.println("Attributes added      : " + totalAttributes);
        System.out.println("Operations added      : " + totalOperations);
    }


    private static int[] processHeaderContent(String content, String fileName,
                                               IRPPackage targetPackage, IRPApplication app) {
        int classCount = 0, attrCount = 0, opCount = 0;

        content = content.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        content = content.replaceAll("//[^\n]*", "");

        Matcher classMatcher = CLASS_PATTERN.matcher(content);

        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String baseClass = classMatcher.group(2);

            if (isKeyword(className)) continue;

            System.out.println("  Class found: " + className +
                (baseClass != null ? " (extends: " + baseClass + ")" : ""));

            IRPClass rhpClass = findOrCreateClass(targetPackage, className);
            if (rhpClass == null) {
                System.err.println("  WARNING: Could not create class: " + className);
                continue;
            }
            classCount++;

            try {
                rhpClass.setPropertyValue("CG.Class.Generate", "No");
            } catch (Exception e) {
                
            }

            try {
                IRPTag tag = (IRPTag) rhpClass.addNewAggr("Tag", "SourceFile");
                tag.setValue(fileName);
            } catch (Exception e) {
                
            }

            if (baseClass != null && !baseClass.isEmpty()) {
                addInheritanceIfNeeded(rhpClass, targetPackage, baseClass);
            }

            int bodyStart = content.indexOf('{', classMatcher.start());
            if (bodyStart == -1) continue;
            String classBody = extractClassBody(content, bodyStart);
            if (classBody == null) continue;

            int[] result = parseClassMembers(rhpClass, classBody, app);
            attrCount += result[0];
            opCount   += result[1];
        }

        return new int[]{classCount, attrCount, opCount};
    }


    private static int[] parseClassMembers(IRPClass rhpClass, String classBody, IRPApplication app) {
        int attrCount = 0, opCount = 0;

        String[] sections = classBody.split("(?=(?:public|private|protected)\\s*:)");
        String currentVisibility = "private";

        for (String section : sections) {
            Matcher visMatcher = VISIBILITY_PATTERN.matcher(section);
            if (visMatcher.find()) {
                currentVisibility = visMatcher.group(1);
            }

            // Parse operations
            Set<String> addedOps = new HashSet<>();
            Matcher opMatcher = OPERATION_PATTERN.matcher(section);
            while (opMatcher.find()) {
                String retType  = opMatcher.group(2).trim();
                String opName   = opMatcher.group(3).trim();
                String paramStr = opMatcher.group(4).trim();

                if (isKeyword(opName)) continue;
                String opKey = opName + "(" + paramStr + ")";
                if (addedOps.contains(opKey)) continue;
                addedOps.add(opKey);

                if (findOperation(rhpClass, opName) == null) {
                    IRPOperation op = rhpClass.addOperation(opName);
                    setOperationReturnType(op, retType, app);
                    op.setVisibility(mapVisibility(currentVisibility));
                    addParameters(op, paramStr, app);
                    System.out.println("    + Operation [" + currentVisibility + "]: " + retType + " " + opName + "(" + paramStr + ")");
                    opCount++;
                } else {
                    System.out.println("    ~ Skipped existing operation: " + opName);
                }
            }

            String attrSection = section.replaceAll(
                "(?m)^\\s*((?:virtual|static|explicit|inline)\\s+)?" +
                "((?:const\\s+)?[\\w:<>*&]+(?:\\s*[*&])?)\\s+" +
                "(\\w+)\\s*\\([^)]*\\)\\s*(?:const)?\\s*(?:override|= 0)?\\s*;", ""
            );

            Matcher attrMatcher = ATTRIBUTE_PATTERN.matcher(attrSection);
            while (attrMatcher.find()) {
                String attrType = attrMatcher.group(1).trim();
                String attrName = attrMatcher.group(2).trim();

                if (isKeyword(attrName) || attrType.isEmpty()) continue;

                if (findAttribute(rhpClass, attrName) == null) {
                    IRPAttribute attr = rhpClass.addAttribute(attrName);
                    // FIX 5: setType needs IRPClassifier
                    setAttributeType(attr, attrType, app);
                    attr.setVisibility(mapVisibility(currentVisibility));
                    System.out.println("    + Attribute [" + currentVisibility + "]: " + attrType + " " + attrName);
                    attrCount++;
                } else {
                    System.out.println("    ~ Skipped existing attribute: " + attrName);
                }
            }
        }

        return new int[]{attrCount, opCount};
    }


    private static IRPClassifier resolveType(String typeName, IRPApplication app) {
        if (typeName == null || typeName.isEmpty()) return null;

        String cleanType = typeName
            .replaceAll("\\bconst\\b", "")
            .replaceAll("[*&]", "")
            .trim();

        try {
            IRPProject project = app.activeProject();

            IRPModelElement el = project.findNestedElement(cleanType, "Type");
            if (el instanceof IRPClassifier) return (IRPClassifier) el;

            el = project.findNestedElement(cleanType, "Class");
            if (el instanceof IRPClassifier) return (IRPClassifier) el;

            IRPCollection packages = project.getPackages();
            for (int i = 1; i <= packages.getCount(); i++) {
                IRPPackage pkg = (IRPPackage) packages.getItem(i);
                el = pkg.findNestedElement(cleanType, "Type");
                if (el instanceof IRPClassifier) return (IRPClassifier) el;
                el = pkg.findNestedElement(cleanType, "Class");
                if (el instanceof IRPClassifier) return (IRPClassifier) el;
            }
        } catch (Exception e) {
         
        }
        return null;
    }

    private static void setAttributeType(IRPAttribute attr, String typeName, IRPApplication app) {
        try {
            IRPClassifier type = resolveType(typeName, app);
            if (type != null) {
                attr.setType(type);
            } else {
                try { attr.setPropertyValue("typeName", typeName); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("      WARNING: Could not set attribute type '" + typeName + "': " + e.getMessage());
        }
    }

    private static void setOperationReturnType(IRPOperation op, String typeName, IRPApplication app) {
        try {
            IRPClassifier type = resolveType(typeName, app);
            if (type != null) {
                op.setReturns(type);
            }

        } catch (Exception e) {
            System.err.println("      WARNING: Could not set return type '" + typeName + "': " + e.getMessage());
        }
    }


    private static void addParameters(IRPOperation op, String paramStr, IRPApplication app) {
        if (paramStr == null || paramStr.trim().isEmpty() || paramStr.trim().equals("void")) return;

        String[] params = paramStr.split(",");
        int paramIndex  = 1;

        for (String param : params) {
            param = param.trim();
            if (param.isEmpty()) continue;

            param = param.replaceAll("=\\s*[^,]+", "").trim();

            Matcher m = Pattern.compile(
                "((?:const\\s+)?[\\w:<>]+(?:\\s*[*&])?)\\s+(\\w+)$"
            ).matcher(param);

            if (m.find()) {
                String paramType = m.group(1).trim();
                String paramName = m.group(2).trim();
                IRPArgument arg  = op.addArgument(paramName);
                // FIX 6: setType needs IRPClassifier
                try {
                    IRPClassifier type = resolveType(paramType, app);
                    if (type != null) arg.setType(type);
                } catch (Exception e) {
                    System.err.println("      WARNING: Could not set param type '" + paramType + "'");
                }
            } else {
                op.addArgument("param" + paramIndex);
            }
            paramIndex++;
        }
    }

    private static String extractClassBody(String content, int openBrace) {
        int depth = 0, i = openBrace;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return content.substring(openBrace + 1, i);
            }
            i++;
        }
        return null;
    }

    private static IRPClass findOrCreateClass(IRPPackage pkg, String className) {
        try {
            IRPModelElement existing = pkg.findNestedElement(className, "Class");
            if (existing instanceof IRPClass) {
                System.out.println("  (Existing class found, updating: " + className + ")");
                return (IRPClass) existing;
            }
            return (IRPClass) pkg.addClass(className);
        } catch (Exception e) {
            System.err.println("  ERROR creating class '" + className + "': " + e.getMessage());
            return null;
        }
    }

    private static IRPPackage findOrCreatePackage(IRPProject project, String packagePath) {
        String[] parts    = packagePath.split("\\.");
        IRPPackage current = null;

        for (String part : parts) {
            if (current == null) {
                IRPModelElement el = project.findNestedElement(part, "Package");
                if (el instanceof IRPPackage) {
                    current = (IRPPackage) el;
                } else {
                    current = (IRPPackage) project.addPackage(part);
                }
            } else {
                IRPModelElement el = current.findNestedElement(part, "Package");
                if (el instanceof IRPPackage) {
                    current = (IRPPackage) el;
                } else {
                    // FIX 7: IRPPackage uses addNestedPackage, not addPackage
                    current = (IRPPackage) current.addNestedPackage(part);
                }
            }
        }
        return current;
    }

    private static void addInheritanceIfNeeded(IRPClass rhpClass, IRPPackage pkg, String baseClassName) {
        try {
            IRPCollection generalizations = rhpClass.getGeneralizations();
            for (int i = 1; i <= generalizations.getCount(); i++) {
                IRPGeneralization gen = (IRPGeneralization) generalizations.getItem(i);
                if (gen.getBaseClass().getName().equals(baseClassName)) return;
            }
            IRPClass baseClass = findOrCreateClass(pkg, baseClassName);
            if (baseClass != null) {
                rhpClass.addGeneralization(baseClass);
                System.out.println("    + Inheritance: " + rhpClass.getName() + " -> " + baseClassName);
            }
        } catch (Exception e) {
            System.err.println("    WARNING: Could not add inheritance to " + baseClassName + ": " + e.getMessage());
        }
    }

    private static IRPOperation findOperation(IRPClass rhpClass, String opName) {
        try {
            IRPCollection ops = rhpClass.getOperations();
            for (int i = 1; i <= ops.getCount(); i++) {
                IRPOperation op = (IRPOperation) ops.getItem(i);
                if (op.getName().equals(opName)) return op;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static IRPAttribute findAttribute(IRPClass rhpClass, String attrName) {
        try {
            IRPCollection attrs = rhpClass.getAttributes();
            for (int i = 1; i <= attrs.getCount(); i++) {
                IRPAttribute attr = (IRPAttribute) attrs.getItem(i);
                if (attr.getName().equals(attrName)) return attr;
            }
        } catch (Exception ignored) {}
        return null;
    }


    private static List<File> collectHeaderFiles(String dirPath) {
        List<File> result = new ArrayList<>();
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("WARNING: Not a valid directory: " + dirPath);
            return result;
        }
        collectHeaderFilesRecursive(dir, result);
        return result;
    }

    private static void collectHeaderFilesRecursive(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectHeaderFilesRecursive(f, result);
            else if (f.getName().endsWith(".h") || f.getName().endsWith(".hpp")) result.add(f);
        }
    }

    private static String mapVisibility(String cppVisibility) {
        switch (cppVisibility.toLowerCase()) {
            case "public":    return "public";
            case "protected": return "protected";
            default:          return "private";
        }
    }

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "else", "for", "while", "do", "switch", "case", "break", "return",
        "continue", "class", "struct", "enum", "namespace", "template", "typename",
        "typedef", "using", "delete", "new", "this", "nullptr", "true", "false",
        "try", "catch", "throw", "const", "static", "virtual", "override", "final",
        "inline", "explicit", "operator", "friend", "extern", "volatile", "mutable"
    ));

    private static boolean isKeyword(String name) {
        return KEYWORDS.contains(name);
    }
}