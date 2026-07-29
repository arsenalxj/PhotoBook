# MinIO references a compile-time FindBugs annotation which is absent at runtime.
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings

# MinIO reflects from each nested Builder to its enclosing *Args class, then invokes the
# no-argument constructor. R8 must not merge or remove either side of that relationship.
-keepattributes InnerClasses,EnclosingMethod
-keep class io.minio.**Args { *; }
-keep class io.minio.**Args$Builder { *; }

# MinIO deserializes S3 XML responses through Simple XML reflection. Generic collection
# element types and Simple XML's own reflectively selected label constructors must survive R8.
-keepattributes Signature,*Annotation*
-keep class io.minio.messages.** { *; }
-keep class org.simpleframework.xml.** { *; }

# Simple XML contains an optional StAX backend; Android falls back to its native XmlPullParser.
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLEventReader
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLResolver
-dontwarn javax.xml.stream.events.Attribute
-dontwarn javax.xml.stream.events.Characters
-dontwarn javax.xml.stream.events.StartElement
-dontwarn javax.xml.stream.events.XMLEvent
