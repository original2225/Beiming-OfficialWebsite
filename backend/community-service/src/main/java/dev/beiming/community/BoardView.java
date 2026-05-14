package dev.beiming.community;

public record BoardView(
  String id,
  String slug,
  String name,
  String description,
  String visibility,
  String postingRole,
  int sortOrder
) {
  static BoardView fromRecord(BoardRecord record) {
    return new BoardView(
      record.id(),
      record.slug(),
      record.name(),
      record.description(),
      record.visibility(),
      record.postingRole(),
      record.sortOrder()
    );
  }
}
