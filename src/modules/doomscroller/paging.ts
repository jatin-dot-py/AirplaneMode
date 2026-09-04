export type SettledReelPage = {
  exactOffset: number;
  index: number;
  needsCorrection: boolean;
};

const ALIGNMENT_TOLERANCE_PX = 0.5;

export function settledReelPage(
  offset: number,
  viewportHeight: number,
  itemCount: number,
): SettledReelPage {
  if (
    !Number.isFinite(offset) ||
    !Number.isFinite(viewportHeight) ||
    viewportHeight <= 0 ||
    itemCount <= 0
  ) {
    return {exactOffset: 0, index: 0, needsCorrection: false};
  }

  const lastIndex = itemCount - 1;
  const boundedOffset = Math.max(
    0,
    Math.min(offset, viewportHeight * lastIndex),
  );
  const index = Math.max(
    0,
    Math.min(Math.round(boundedOffset / viewportHeight), lastIndex),
  );
  const exactOffset = index * viewportHeight;

  return {
    exactOffset,
    index,
    needsCorrection: Math.abs(offset - exactOffset) > ALIGNMENT_TOLERANCE_PX,
  };
}

export function settledReelIndex(
  offset: number,
  viewportHeight: number,
  itemCount: number,
) {
  return settledReelPage(offset, viewportHeight, itemCount).index;
}
