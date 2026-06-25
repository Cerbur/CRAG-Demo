package ai.cerbur.crag.knowledge.core.document;

/**
 * 上传文件类型，首版支持 {@link #TXT} 与 {@link #MARKDOWN}。
 *
 * <p>类型由原始文件名扩展名推导，并必须与客户端声明的类型一致。数据库与 proto 中以 {@link #name()} 大写形式持久化与传输。
 */
public enum FileType {
  TXT("txt"),
  MARKDOWN("md");

  private final String extension;

  FileType(String extension) {
    this.extension = extension;
  }

  /** 对应的小写扩展名（不含点）。 */
  public String extension() {
    return extension;
  }

  /** 按原始文件名推导类型；扩展名非法时抛 {@link IllegalArgumentException}。 */
  public static FileType fromFilename(String originalFilename) {
    if (originalFilename == null) {
      throw new IllegalArgumentException("originalFilename must not be null");
    }
    int dot = originalFilename.lastIndexOf('.');
    if (dot < 0 || dot == originalFilename.length() - 1) {
      throw new IllegalArgumentException("originalFilename must have a supported extension");
    }
    String ext = originalFilename.substring(dot + 1).toLowerCase();
    for (FileType type : values()) {
      if (type.extension.equals(ext)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unsupported file extension: " + ext);
  }

  /** 解析客户端声明类型字符串（大小写不敏感）；非法时抛 {@link IllegalArgumentException}。 */
  public static FileType fromDeclared(String declared) {
    if (declared == null) {
      throw new IllegalArgumentException("fileType must not be null");
    }
    return FileType.valueOf(declared.trim().toUpperCase());
  }
}
