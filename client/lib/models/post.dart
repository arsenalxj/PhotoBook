enum PostMediaType {
  image,
  video;

  static PostMediaType parse(Object? value) {
    return PostMediaType.values.firstWhere(
      (type) => type.name == value,
      orElse: () => throw FormatException('未知媒体类型：$value'),
    );
  }
}

class PostMedia {
  const PostMedia({
    required this.id,
    required this.mediaType,
    required this.width,
    required this.height,
    this.localThumbnailPath,
    this.localOriginalPath,
  });

  final String id;
  final PostMediaType mediaType;
  final int width;
  final int height;
  final String? localThumbnailPath;
  final String? localOriginalPath;

  double get aspectRatio => width / height;
}

class ArchivedPost {
  const ArchivedPost({
    required this.id,
    required this.sourceUrl,
    required this.authorUsername,
    required this.authorDisplayName,
    required this.caption,
    required this.publishedAt,
    required this.coverMediaId,
    required this.mediaCount,
    required this.media,
    this.locationName,
    this.localAvatarPath,
  });

  final String id;
  final String sourceUrl;
  final String authorUsername;
  final String authorDisplayName;
  final String caption;
  final int publishedAt;
  final String? locationName;
  final String coverMediaId;
  final int mediaCount;
  final String? localAvatarPath;
  final List<PostMedia> media;

  PostMedia get coverMedia => media.firstWhere(
    (item) => item.id == coverMediaId,
    orElse: () => media.first,
  );
}
