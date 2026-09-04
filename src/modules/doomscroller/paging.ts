export function settledReelIndex(
  offset: number,
  viewportHeight: number,
  itemCount: number,
) {
  if (
    !Number.isFinite(offset) ||
    !Number.isFinite(viewportHeight) ||
    viewportHeight <= 0 ||
    itemCount <= 0
  ) {
    return 0;
  }

  const lastIndex = itemCount - 1;
  const boundedOffset = Math.max(
    0,
    Math.min(offset, viewportHeight * lastIndex),
  );
  return Math.max(
    0,
    Math.min(Math.round(boundedOffset / viewportHeight), lastIndex),
  );
}
