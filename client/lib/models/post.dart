enum PostSourcePlatform {
  instagram,
  xiaohongshu;

  static PostSourcePlatform parse(Object? value) =>
      PostSourcePlatform.values.firstWhere(
        (platform) => platform.name == value,
        orElse: () => throw FormatException('未知来源平台：$value'),
      );
}

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

enum PostMediaRole {
  primary,
  liveStill,
  liveMotion;

  static PostMediaRole parse(Object? value) => switch (value) {
    'primary' => PostMediaRole.primary,
    'live_still' => PostMediaRole.liveStill,
    'live_motion' => PostMediaRole.liveMotion,
    _ => throw FormatException('未知媒体角色：$value'),
  };
}

enum MediaExportMode {
  original('original'),
  staticImage('static'),
  gif('gif'),
  video('video');

  const MediaExportMode(this.wireValue);
  final String wireValue;
}

class PostMedia {
  const PostMedia({
    required this.id,
    required this.mediaType,
    required this.width,
    required this.height,
    this.localThumbnailPath,
    this.localOriginalPath,
    this.sortIndex = 0,
    this.logicalIndex = 0,
    this.mediaRole = PostMediaRole.primary,
    this.mimeType = 'image/jpeg',
    this.liveMotion,
  });

  final String id;
  final PostMediaType mediaType;
  final int sortIndex;
  final int logicalIndex;
  final PostMediaRole mediaRole;
  final String mimeType;
  final int width;
  final int height;
  final String? localThumbnailPath;
  final String? localOriginalPath;
  final PostMedia? liveMotion;

  double get aspectRatio => width / height;
  bool get isGif => mimeType.toLowerCase() == 'image/gif';
  bool get isLivePhoto => mediaRole == PostMediaRole.liveStill;
  bool get hasLiveMotion => liveMotion != null;

  PostMedia copyWith({PostMedia? liveMotion}) => PostMedia(
    id: id,
    mediaType: mediaType,
    width: width,
    height: height,
    localThumbnailPath: localThumbnailPath,
    localOriginalPath: localOriginalPath,
    sortIndex: sortIndex,
    logicalIndex: logicalIndex,
    mediaRole: mediaRole,
    mimeType: mimeType,
    liveMotion: liveMotion,
  );
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
    this.sourcePlatform = PostSourcePlatform.instagram,
    this.isBackedUp = false,
  });

  final String id;
  final PostSourcePlatform sourcePlatform;
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
  final bool isBackedUp;

  PostMedia get coverMedia => media.firstWhere(
    (item) => item.id == coverMediaId,
    orElse: () => media.first,
  );
}
