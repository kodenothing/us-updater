# UsApp UI polish test checklist

This checklist covers the front-camera orientation, map layout, and custom bottom-navigation changes added to the v2.5.1 debug branch.

## Front-camera Our Instant

- [ ] Open Our Instant and switch to the front camera.
- [ ] Capture a photo containing readable text or an asymmetric object.
- [ ] Wait for the upload to finish and check the shared home-screen widget on both phones.
- [ ] Confirm the widget photo matches the front-camera preview orientation and text is not reversed.
- [ ] Confirm the shared thumbnail and full-size widget image use the same orientation.
- [ ] Switch to the back camera and confirm its photo is not horizontally flipped.
- [ ] Confirm photo rotation remains correct when the phone is held in portrait and landscape.

## Map layout

- [ ] Open Our Map and confirm the map remains visible above the custom bottom navigation.
- [ ] Confirm the collapsed location-sharing panel does not cover the bottom-navigation icons or heart button.
- [ ] Expand the location-sharing panel and confirm its settings can scroll.
- [ ] Collapse the panel and confirm the recenter control and map attribution remain accessible.
- [ ] Test both phones sharing live locations; verify markers, distance, and route still update.
- [ ] Confirm the map can still be long-pressed to save a place.

## Bottom navigation

- [ ] Visit Home, Calendar, Notes, Memories, and Lists using the bottom navigation.
- [ ] Confirm the selected tab indicator and heart-shaped Home action are clear.
- [ ] Confirm the navigation surface is readable in light and dark themes.
- [ ] Open the chat composer and keyboard; confirm navigation hides while typing and returns after the keyboard closes.
- [ ] Confirm Map, Us Chat, Settings, and Our Instant remain accessible from the top bar.

Do not mark a test complete until it has been checked on a device.
