# Home-screen navigation and Our Instant widget tests

## Minimal hamburger menu

- [ ] Hamburger button opens the left-side drawer.
- [ ] Drawer shows only Our Instant, Our Map, and Settings.
- [ ] Selecting each destination changes to the correct existing screen and closes the drawer.
- [ ] Us Chat remains the only right-side top-bar action and opens correctly.
- [ ] Drawer closes through outside tap and Android Back.
- [ ] Drawer colors are readable in light and dark mode.
- [ ] Drawer does not interfere with the chat keyboard.

## Our Instant image widget design picker

- [ ] Add a new **Our Instant** widget from the Android home-screen widget picker.
- [ ] Confirm the design picker opens before the widget is placed.
- [ ] Select **Soft corners** and confirm the default pink rounded frame appears.
- [ ] Add another instance and select **Fine outline**; confirm the lavender border appears.
- [ ] Add another instance and select **Polaroid**; confirm the white frame and caption appear.
- [ ] Add another instance and select **Minimal**; confirm the thin line and tighter corners appear.
- [ ] Confirm every instance displays the same currently shared Our Instant photo while keeping its own independent frame style.
- [ ] Change the shared photo and verify all widget instances refresh without losing their individual designs.
- [ ] Resize each widget and confirm its selected style remains applied.
- [ ] Tap a widget and confirm it still opens Us.
- [ ] Use the launcher's reconfigure action, where supported, and confirm a new design can be selected.
- [ ] Remove a widget and confirm its per-instance style preference is cleaned up.
- [ ] Confirm existing widgets without a saved style keep the Soft corners default.

Do not mark a test complete until it has been checked on a device. Launcher behavior can vary by Android version and home-screen app.
