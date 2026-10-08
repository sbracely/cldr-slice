package io.github.sbracely;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

public final class CldrSlicer {
    static final String BUNDLE_NAME = "chinese-calendar";
    static final String GENERATED_HEADER =
            "# Generated from Unicode CLDR; see the source distribution LICENSE.";
    private static final String CALENDARS = "/ldml/dates/calendars/calendar";
    private final Path commonDirectory;
    private final XPath xpath = XPathFactory.newInstance().newXPath();
    private final Map<String, XPathExpression> expressions = new HashMap<>();
    private final Map<Document, Map<String, Node>> queriedNodes = new IdentityHashMap<>();
    private final Map<String, Document> documents = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Document> eldest) {
            if (size() > 32) {
                queriedNodes.remove(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private final Map<String, String> parents = new HashMap<>();

    public CldrSlicer(Path commonDirectory) throws IOException {
        this.commonDirectory = Objects.requireNonNull(commonDirectory, "commonDirectory");
        Document supplemental = read("supplemental/supplementalData.xml");
        NodeList nodes = nodes(supplemental,
                "/supplementalData/parentLocales[not(@component)]/parentLocale");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element parent = (Element) nodes.item(i);
            for (String locale : parent.getAttribute("locales").split("\\s+")) {
                if (!locale.isBlank()) {
                    parents.put(locale, parent.getAttribute("parent"));
                }
            }
        }
    }

    public Map<String, Map<String, String>> sliceAll() throws IOException {
        Map<String, String> root = slice("root");
        Map<String, Map<String, String>> resolved = new LinkedHashMap<>();
        resolved.put("root", root);
        List<String> locales;
        try (var files = Files.list(commonDirectory.resolve("main"))) {
            locales = files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".xml") && !name.equals("root.xml"))
                    .map(name -> name.substring(0, name.length() - ".xml".length()))
                    .sorted()
                    .toList();
        }
        ResourceBundle.Control control = ResourceBundle.Control.getControl(
                ResourceBundle.Control.FORMAT_PROPERTIES);
        Map<String, String> bundleLocales = new HashMap<>();
        bundleLocales.put(BUNDLE_NAME, "root");
        Map<String, Set<String>> overrides = new HashMap<>();
        for (String locale : locales) {
            String bundleName = control.toBundleName(BUNDLE_NAME, javaLocale(locale));
            if (bundleLocales.putIfAbsent(bundleName, locale) != null) {
                throw new IOException("Multiple CLDR locales map to the same Java bundle: " + bundleName);
            }
            resolved.put(locale, slice(locale));
            overrides.put(locale, new HashSet<>());
        }
        // Candidate chains can differ for script/region combinations, so check every chain.
        for (String locale : locales) {
            String child = null;
            for (Locale candidate : control.getCandidateLocales(BUNDLE_NAME, javaLocale(locale))) {
                String parent = bundleLocales.get(control.toBundleName(BUNDLE_NAME, candidate));
                if (parent == null) {
                    continue;
                }
                if (child != null) {
                    Map<String, String> childValues = resolved.get(child);
                    Map<String, String> parentValues = resolved.get(parent);
                    for (String key : root.keySet()) {
                        if (childValues.get(key).equals(root.get(key))
                                && !childValues.get(key).equals(parentValues.get(key))) {
                            overrides.get(child).add(key);
                        }
                    }
                }
                child = parent;
            }
        }
        Map<String, Map<String, String>> slices = new LinkedHashMap<>();
        slices.put("root", root);
        for (String locale : locales) {
            Map<String, String> differences = new LinkedHashMap<>();
            resolved.get(locale).forEach((key, value) -> {
                if (!value.equals(root.get(key)) || overrides.get(locale).contains(key)) {
                    differences.put(key, value);
                }
            });
            String bundleName = control.toBundleName(BUNDLE_NAME, javaLocale(locale));
            // Even an empty bundle prevents fallback to the JVM's default locale.
            slices.put(bundleName.substring((BUNDLE_NAME + "_").length()), differences);
        }
        return slices;
    }

    private static Locale javaLocale(String locale) {
        return new Locale.Builder().setLanguageTag(locale.replace('_', '-')).build();
    }

    public Map<String, String> slice(String locale) throws IOException {
        locale = normalizeLocale(locale);
        document(locale);
        Map<String, String> values = new LinkedHashMap<>();
        String calendarName = resolve(locale, locale,
                "/ldml/localeDisplayNames/types/type[@key='calendar'][@type='chinese']"
                        + "[not(@alt)][not(@scope)]", new HashSet<>());
        // CLDR display names use the identifier as code fallback when root has no name.
        values.put("name", calendarName == null ? "chinese" : calendarName);
        for (int month = 1; month <= 12; month++) {
            values.put("month." + month, require(locale,
                    CALENDARS + "[@type='chinese']/months"
                            + "/monthContext[@type='format']/monthWidth[@type='wide']"
                            + "/month[@type='" + month + "'][not(@yeartype)][not(@alt)]"));
        }
        for (int month = 1; month <= 12; month++) {
            values.put("month.narrow." + month, require(locale,
                    CALENDARS + "[@type='chinese']/months"
                            + "/monthContext[@type='format']/monthWidth[@type='narrow']"
                            + "/month[@type='" + month + "'][not(@yeartype)][not(@alt)]"));
        }
        values.put("month.leapPattern", require(locale,
                CALENDARS + "[@type='chinese']/monthPatterns/monthPatternContext[@type='format']"
                        + "/monthPatternWidth[@type='wide']/monthPattern[@type='leap']"));
        for (int term = 1; term <= 24; term++) {
            values.put("solarTerm." + term, require(locale,
                    CALENDARS + "[@type='chinese']/cyclicNameSets/cyclicNameSet[@type='solarTerms']"
                            + "/cyclicNameContext[@type='format']/cyclicNameWidth[@type='abbreviated']"
                            + "/cyclicName[@type='" + term + "'][not(@alt)]"));
        }
        for (int year = 1; year <= 60; year++) {
            values.put("sexagenary." + year, require(locale,
                    CALENDARS + "[@type='chinese']/cyclicNameSets/cyclicNameSet[@type='years']"
                            + "/cyclicNameContext[@type='format']/cyclicNameWidth[@type='abbreviated']"
                            + "/cyclicName[@type='" + year + "'][not(@alt)]"));
        }
        for (int zodiac = 1; zodiac <= 12; zodiac++) {
            values.put("zodiac." + zodiac, require(locale,
                    CALENDARS + "[@type='chinese']/cyclicNameSets/cyclicNameSet[@type='zodiacs']"
                            + "/cyclicNameContext[@type='format']/cyclicNameWidth[@type='abbreviated']"
                            + "/cyclicName[@type='" + zodiac + "'][not(@alt)]"));
        }
        return values;
    }

    public static String normalizeLocale(String locale) {
        String normalized = locale.replace('-', '_');
        if (!normalized.matches("[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid CLDR locale: " + locale);
        }
        return normalized;
    }

    private String require(String locale, String path) throws IOException {
        String value = resolve(locale, locale, path, new HashSet<>());
        if (value == null) {
            throw new IOException("Missing CLDR value for " + locale + ": " + path);
        }
        return value;
    }

    private String resolve(String requested, String locale, String path, Set<String> visited)
            throws IOException {
        if (!visited.add(requested + ":" + locale + ":" + path)) {
            throw new IOException("CLDR inheritance/alias cycle for " + locale + ": " + path);
        }
        Document document = document(locale);
        Node value = node(document, path);
        if (value != null) {
            String text = value.getTextContent().trim();
            if (text.equals("\u2205\u2205\u2205")) {
                throw new IOException("CLDR explicitly blocks inheritance for " + locale + ": " + path);
            }
            if (!text.isEmpty() && !text.equals("\u2191\u2191\u2191")) {
                return text;
            }
        }
        // An alias can redirect an entire subtree, not just an individual value.
        String prefix = path;
        while (prefix.contains("/")) {
            Node alias = node(document, prefix + "/alias");
            if (alias instanceof Element element) {
                String source = element.getAttribute("source");
                String targetLocale = source.equals("locale") ? requested : normalizeLocale(source);
                Node target = node(alias.getParentNode(), element.getAttribute("path"));
                if (target == null) {
                    throw new IOException("Invalid CLDR alias in " + locale + ": " + prefix);
                }
                return resolve(targetLocale, targetLocale,
                        absolutePath(target) + path.substring(prefix.length()), visited);
            }
            prefix = prefix.substring(0, prefix.lastIndexOf('/'));
        }
        if (locale.equals("root")) {
            return null;
        }
        String parent = parents.get(locale);
        if (parent == null) {
            int separator = locale.lastIndexOf('_');
            parent = separator < 0 ? "root" : locale.substring(0, separator);
        }
        return resolve(requested, parent, path, visited);
    }

    private static String absolutePath(Node node) {
        if (!(node instanceof Element element)) {
            return "";
        }
        String step = "/" + element.getTagName();
        if (element.hasAttribute("type")) {
            step += "[@type='" + element.getAttribute("type") + "']";
        }
        return absolutePath(node.getParentNode()) + step;
    }

    private Document document(String locale) throws IOException {
        Document document = documents.get(locale);
        if (document == null) {
            document = read("main/" + locale + ".xml");
            documents.put(locale, document);
        }
        return document;
    }

    private Document read(String relative) throws IOException {
        Path file = commonDirectory.resolve(relative.replace('/', '\\'));
        try (InputStream input = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(input);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot parse CLDR file " + relative + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IOException("Cannot read CLDR file " + file.toAbsolutePath()
                    + ": " + e.getMessage(), e);
        }
    }

    private Node node(Node context, String expression) throws IOException {
        try {
            if (context instanceof Document document) {
                Map<String, Node> results = queriedNodes.computeIfAbsent(document,
                        ignored -> new HashMap<>());
                if (!results.containsKey(expression)) {
                    results.put(expression,
                            (Node) expression(expression).evaluate(context, XPathConstants.NODE));
                }
                return results.get(expression);
            }
            return (Node) expression(expression).evaluate(context, XPathConstants.NODE);
        } catch (XPathExpressionException e) {
            throw new IOException("Invalid CLDR XPath: " + expression, e);
        }
    }

    private NodeList nodes(Node context, String expression) throws IOException {
        try {
            return (NodeList) expression(expression).evaluate(context, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new IOException("Invalid CLDR XPath: " + expression, e);
        }
    }

    private XPathExpression expression(String path) throws XPathExpressionException {
        XPathExpression expression = expressions.get(path);
        if (expression == null) {
            expression = xpath.compile(path);
            expressions.put(path, expression);
        }
        return expression;
    }

    public static void write(Path file, Map<String, String> values) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            content.append(escape(entry.getKey())).append('=')
                    .append(escape(entry.getValue())).append('\n');
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            escaped.append(switch (character) {
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\f' -> "\\f";
                case '=', ':', '#', '!', ' ' -> "\\" + character;
                default -> String.valueOf(character);
            });
        }
        return escaped.toString();
    }
}
