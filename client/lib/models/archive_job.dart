class ArchiveJob {
  const ArchiveJob({
    required this.id,
    required this.sourcePostId,
    this.errorCode,
    this.errorMessage,
  });

  final String id;
  final String sourcePostId;
  final String? errorCode;
  final String? errorMessage;

  factory ArchiveJob.fromDatabase(Map<String, Object?> row) => ArchiveJob(
    id: row['id']! as String,
    sourcePostId: row['source_post_id']! as String,
    errorCode: row['error_code'] as String?,
    errorMessage: row['error_message'] as String?,
  );

  String get failureTitle => switch (errorCode) {
    'LOGIN_REQUIRED' => '需要 Instagram 登录',
    'POST_UNAVAILABLE' => '帖子不可访问',
    'INSTAGRAM_ERROR' || 'INVALID_RESPONSE' || 'SOURCE_MISMATCH' => '帖子解析失败',
    'NETWORK_ERROR' => '网络连接失败',
    'RATE_LIMITED' => 'Instagram 请求受限',
    'MEDIA_DOWNLOAD_FAILED' => '媒体下载失败',
    'THUMBNAIL_FAILED' => '缩略图生成失败',
    'MEDIA_STORE_FAILED' => '本地媒体保存失败',
    'INTERNAL_ERROR' => '应用内部错误',
    _ => '帖子归档失败',
  };

  String get failureDetail {
    final detail = errorMessage?.trim();
    if (detail != null && detail.isNotEmpty) return detail;
    return switch (errorCode) {
      'LOGIN_REQUIRED' => 'Instagram 要求登录，请在官方登录页完成验证后重试。',
      'POST_UNAVAILABLE' => '帖子不存在、已删除、已设为私密或当前不可访问。',
      'INSTAGRAM_ERROR' ||
      'INVALID_RESPONSE' ||
      'SOURCE_MISMATCH' => 'Instagram 返回的帖子数据无法解析，请稍后重试。',
      'NETWORK_ERROR' => '无法连接 Instagram，请检查系统网络或 VPN。',
      'RATE_LIMITED' => 'Instagram 请求过于频繁，请稍后重试。',
      'MEDIA_DOWNLOAD_FAILED' => '原图或视频下载失败，请检查帖子是否仍可访问。',
      'THUMBNAIL_FAILED' => '原媒体已读取，但无法生成预览图。',
      'MEDIA_STORE_FAILED' => '媒体无法写入本机存储。',
      'INTERNAL_ERROR' => '保存帖子时发生内部错误。',
      _ => '没有返回具体错误信息。',
    };
  }
}
