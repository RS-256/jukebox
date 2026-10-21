# Stonecutter Fabric template
## Setup
1. Review the supported Minecraft versions in `settings.gradle.kts`.
   For new entries, add `versions/.../gradle.properties` with the same keys as other versions.
2. Change `mod.group`, `mod.id` and `mod.name` properties in `gradle.properties`.
3. Rename `com.example` package in `src/main/java`.
4. Rename `src/main/resources/template.mixins.json` to use your mod's id.
5. Review the `LICENSE` file. 
   See the [license decision diagram](https://docs.codeberg.org/getting-started/licensing/#license-decision-diagram) for common options.
6. Review `src/main/resources/fabric.mod.json` to have up-to-date properties.

## Usage
- Use `"Set active project to ..."` Gradle tasks to update the Minecraft version
  available in `src/` classes.
- Use `buildAndCollect` Gradle task to store mod releases in `build/libs/`.
- Enable `mod-publish-plugin` in `stonecutter.gradle.kts` and `build.gradle.kts`
  and the corresponding code blocks to publish releases to Modrinth and Curseforge.
- Enable `maven-publish` in `build.gradle.kts` and the corresponding code block
  to publish releases to a personal maven repository.

## Useful links
- [Stonecutter beginner's guide](https://stonecutter.kikugie.dev/wiki/start/): *spoiler: you* ***need*** *to understand how it works!*
- [Fabric Discord server](https://discord.gg/v6v4pMv): for mod development help.
- [Stonecutter Discord server](https://discord.kikugie.dev/): for Stonecutter and Gradle help.
- [How To Ask Questions - the guide](http://www.catb.org/esr/faqs/smart-questions.html): also in [video form](https://www.youtube.com/results?search_query=How+To+Ask+Questions+The+Smart+Way).

## Commonly used syntax
### minecraft version specific code
you can use `if` block in your source code (not in the build script) to apply or remove minecraft version specific code.
When the stonecutter evaluate the codes, for example; reload the gradle, building, ofc runClient or something else, 
if the current active version (defined at `stonecutter.gradle.kts#L15`) does not meet the conditions, that code is commented out in a comment block
```java
//some code...
//? if >=1.21.3 {
//version specific code...
//?}
// another some code...
```
ofc you can also use `else` and `else if` block, like this.
```java
public static MutableComponent literal(String string) {
    //? if <=1.18.2 {
    // only uncommented and can be evaluated if current version is under 1.18.2 (<=1.18.2)
    /*return new TextComponent(string);
     *///?} else {
    return Component.literal(string);
    //?}
}
```
and you can nest the `if` block up to 10 times.
```java
//? if <=1.18.2 {
/*public void registerCommands() {
	CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> InfinoteCommand.register(dispatcher));
}
 *///?} else {
public void registerCommands() {
	CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
		TemplateCommand.register(dispatcher, registryAccess);
		//? if >=1.20.3 {
        // only uncommented and can be evaluated if current version is >=1.18.2 && 1.20.3

        ExampleCommand.register(dispatcher);
		//?}
	});
}
//?}
```
if the version specific code is only one line and in the `if` block, not `else` or `else if`, you can omit the bracket, but I recommend not.

