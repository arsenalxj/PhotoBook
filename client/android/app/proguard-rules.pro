# MinIO references a compile-time FindBugs annotation which is absent at runtime.
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings

# simple-xml-safe contains an optional StAX backend; Android uses its native XML backend.
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLEventReader
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLResolver
-dontwarn javax.xml.stream.events.Attribute
-dontwarn javax.xml.stream.events.Characters
-dontwarn javax.xml.stream.events.StartElement
-dontwarn javax.xml.stream.events.XMLEvent
