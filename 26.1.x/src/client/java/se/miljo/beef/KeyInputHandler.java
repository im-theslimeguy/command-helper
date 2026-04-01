package se.miljo.beef;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;

public final class KeyInputHandler {
	private static final String KEY_CATEGORY = "key.categories.misc";
	private static final String KEY_ID = "key.command-helper.show_details";

	private static Object detailKeyMapping;
	private static boolean detailKeyPressed;
	private static boolean keyRegistered;
	private static boolean loggedCtorDump;
	private static boolean loggedKeyMethods;
	private static boolean loggedMappingMethods;

	private KeyInputHandler() {
	}

	public static void initialize() {
		detailKeyMapping = createKeyMapping();
		if (detailKeyMapping != null) {
			// onInitializeClient a Minecraft konstruktorának elején fut (options még null),
			// ezért a Fabric KeyBindingHelper az ideális út — az options.load() ELŐTT regisztrál,
			// így a mentett kötés visszatöltödik. Ha a helper nem elérhető, a tick callback-
			// ben végzünk array-alapú fallback-et (ahol options már nem null).
			tryRegisterWithFabricHelper();
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!keyRegistered && detailKeyMapping != null && client.options != null) {
				registerKeyMapping(client.options);
				keyRegistered = true;
			}
			if (client.player == null) {
				detailKeyPressed = false;
				return;
			}
			detailKeyPressed = checkKeyPressedGlfw();
		});
	}

	public static boolean isDetailKeyPressed() {
		return detailKeyPressed;
	}

	private static Object createKeyMapping() {
		try {
			Class<?> keyMappingClass = Class.forName("net.minecraft.client.KeyMapping");
			if (!loggedCtorDump) {
				loggedCtorDump = true;
				StringBuilder sb = new StringBuilder("Command Helper: KeyMapping ctors: ");
				for (Constructor<?> ctor : keyMappingClass.getConstructors()) {
					sb.append('(');
					Class<?>[] p = ctor.getParameterTypes();
					for (int i = 0; i < p.length; i++) {
						if (i > 0) sb.append(", ");
						sb.append(p[i].getName());
					}
					sb.append(") ");
				}
				CommandHelper.LOGGER.info(sb.toString());
			}
			for (Constructor<?> constructor : keyMappingClass.getConstructors()) {
				java.util.List<Object[]> candidates = buildCtorArgCandidates(constructor.getParameterTypes());
				for (Object[] args : candidates) {
					try {
						return constructor.newInstance(args);
					} catch (ReflectiveOperationException ignored) {
						// Try next candidate.
					}
				}
			}
			throw new ReflectiveOperationException("No compatible KeyMapping constructor found.");
		} catch (ReflectiveOperationException ex) {
			CommandHelper.LOGGER.warn("Command Helper: failed to create configurable key mapping.", ex);
			return null;
		}
	}

	private static java.util.List<Object[]> buildCtorArgCandidates(Class<?>[] types) throws ReflectiveOperationException {
		java.util.List<Object[]> candidates = new java.util.ArrayList<>();
		Object[] args = new Object[types.length];
		boolean hadKeyInput = false;
		boolean hadName = false;

		Class<?> inputTypeClass = null;
		Object keySym = null;
		Object inputKey = null;

		for (int i = 0; i < types.length; i++) {
			Class<?> type = types[i];
			String typeName = type.getName();

			if (type == String.class) {
				args[i] = KEY_ID;
				hadName = true;
				continue;
			}
			if (type == int.class || type == Integer.class) {
				args[i] = GLFW.GLFW_KEY_LEFT_ALT;
				hadKeyInput = true;
				continue;
			}
			if (type == boolean.class || type == Boolean.class) {
				args[i] = false;
				continue;
			}
			if (type == float.class || type == Float.class) {
				args[i] = 0.0F;
				continue;
			}
			if (typeName.endsWith("InputConstants$Type")) {
				if (inputTypeClass == null) {
					inputTypeClass = Class.forName("com.mojang.blaze3d.platform.InputConstants$Type");
					keySym = getEnumConstant(inputTypeClass, "KEYSYM");
				}
				if (keySym == null) {
					return null;
				}
				args[i] = keySym;
				continue;
			}
			if (typeName.endsWith("InputConstants$Key")) {
				if (inputTypeClass == null) {
					inputTypeClass = Class.forName("com.mojang.blaze3d.platform.InputConstants$Type");
					keySym = getEnumConstant(inputTypeClass, "KEYSYM");
				}
				if (keySym == null) {
					return null;
				}
				if (inputKey == null) {
					Method getOrCreate = inputTypeClass.getMethod("getOrCreate", int.class);
					inputKey = getOrCreate.invoke(keySym, GLFW.GLFW_KEY_LEFT_ALT);
				}
				args[i] = inputKey;
				hadKeyInput = true;
				continue;
			}
			if (type.isEnum()) {
				Object enumValue = getEnumConstant(type, "MISCELLANEOUS");
				if (enumValue == null) enumValue = getEnumConstant(type, "MISC");
				if (enumValue == null) enumValue = getEnumConstant(type, "GAMEPLAY");
				if (enumValue == null) enumValue = getEnumConstant(type, "GENERAL");
				if (enumValue == null) {
					Object[] constants = type.getEnumConstants();
					if (constants != null && constants.length > 0) {
						enumValue = constants[0];
					}
				}
				if (enumValue == null) {
					return null;
				}
				args[i] = enumValue;
				continue;
			}
			// Unknown object parameter: try null and hope ctor accepts it.
			if (!type.isPrimitive()) {
				args[i] = null;
				continue;
			}
			return candidates;
		}

		if (hadName && hadKeyInput) {
			candidates.add(args);
			// Alternate candidate for constructors with more than one String.
			int stringCount = 0;
			for (Class<?> t : types) {
				if (t == String.class) stringCount++;
			}
			if (stringCount > 1) {
				Object[] alt = args.clone();
				boolean firstDone = false;
				for (int i = 0; i < types.length; i++) {
					if (types[i] == String.class) {
						if (!firstDone) {
							alt[i] = KEY_CATEGORY;
							firstDone = true;
						} else {
							alt[i] = KEY_ID;
							break;
						}
					}
				}
				candidates.add(alt);
			}
		}
		return candidates;
	}

	private static boolean checkKeyPressedGlfw() {
		long handle = GLFW.glfwGetCurrentContext();
		if (handle == 0L) {
			return false;
		}
		int keyCode = resolveBoundKeyCode();
		if (keyCode <= 0) {
			return false;
		}
		int state = GLFW.glfwGetKey(handle, keyCode);
		return state == GLFW.GLFW_PRESS || state == GLFW.GLFW_REPEAT;
	}

	private static int resolveBoundKeyCode() {
		if (detailKeyMapping == null) {
			return GLFW.GLFW_KEY_UNKNOWN;
		}
		// Egyszer logoljuk az összes 0-paraméteres metódust a KeyMapping objektumon.
		if (!loggedMappingMethods) {
			loggedMappingMethods = true;
			StringBuilder sb = new StringBuilder("CH KeyMapping (")
				.append(detailKeyMapping.getClass().getSimpleName()).append(") methods: ");
			for (Method m : detailKeyMapping.getClass().getMethods()) {
				if (m.getParameterCount() != 0) continue;
				try {
					Object val = m.invoke(detailKeyMapping);
					sb.append(m.getName()).append('=').append(val).append(' ');
				} catch (ReflectiveOperationException ignored) {}
			}
			CommandHelper.LOGGER.info(sb.toString());
		}
		Object keyObj = null;
		// fabric_getBoundKey = Fabric API által injektált metódus, az AKTUÁLISAN kötött gombot adja
		// getDefaultKey = az ALAPÉRTELMEZETT gombot adja vissza, ezt NEM akarjuk előre
		for (String methodName : new String[]{"fabric_getBoundKey", "getKey", "key", "getBoundKey", "getBinding", "currentKey"}) {
			try {
				Method method = detailKeyMapping.getClass().getMethod(methodName);
				if (method.getParameterCount() != 0) continue;
				keyObj = method.invoke(detailKeyMapping);
				if (keyObj != null) break;
			} catch (ReflectiveOperationException ignored) {
				// Következő getter.
			}
		}
		if (keyObj == null) {
			return GLFW.GLFW_KEY_UNKNOWN;
		}
		// Egyszer logoljuk az összes 0-paraméteres metódust a Key objektumon,
		// hogy lássuk a pontos metódusnevet és értéket MC 26.1-ben.
		if (!loggedKeyMethods) {
			loggedKeyMethods = true;
			StringBuilder sb = new StringBuilder("CH key object (").append(keyObj.getClass().getSimpleName()).append(") methods: ");
			for (Method m : keyObj.getClass().getMethods()) {
				if (m.getParameterCount() != 0) continue;
				try {
					Object val = m.invoke(keyObj);
					sb.append(m.getName()).append('=').append(val).append(' ');
				} catch (ReflectiveOperationException ignored) {}
			}
			CommandHelper.LOGGER.info(sb.toString());
		}
		for (String keyMethod : new String[]{"getValue", "value", "getCode", "code", "getKeyCode", "keyCode", "getId", "id", "getType"}) {
			try {
				Method km = keyObj.getClass().getMethod(keyMethod);
				if (km.getParameterCount() != 0) continue;
				Object value = km.invoke(keyObj);
				if (value instanceof Number number) {
					int code = number.intValue();
					if (code > 0) return code;
				}
			} catch (ReflectiveOperationException ignored) {
				// Következő accessor.
			}
		}
		return GLFW.GLFW_KEY_UNKNOWN;
	}

	private static Object getEnumConstant(Class<?> enumClass, String name) {
		if (!enumClass.isEnum()) {
			return null;
		}
		Object[] constants = enumClass.getEnumConstants();
		for (Object constant : constants) {
			if (constant instanceof Enum<?> enumValue && enumValue.name().equals(name)) {
				return enumValue;
			}
		}
		return null;
	}

	/**
	 * onInitializeClient idején (Minecraft konstruktor eleje, options még null) próbálja
	 * meg a Fabric helper-t. Több lehetséges osztály- és metódusnévvel próbálkozik,
	 * mert MC 26.1-ben átnevezték/átcsomagolták.
	 */
	private static void tryRegisterWithFabricHelper() {
		String[] classNames = {
			"net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper",
			"net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper",
			"net.fabricmc.fabric.api.client.keymapping.v1.KeyBindingHelper",
			"net.fabricmc.fabric.api.client.keybinding.v1.KeyMappingHelper",
			"net.fabricmc.fabric.impl.client.keybinding.KeyBindingRegistryImpl",
		};
		String[] methodNames = {"registerKeyBinding", "registerKeyMapping", "register"};

		for (String className : classNames) {
			Class<?> helperClass;
			try {
				helperClass = Class.forName(className);
			} catch (ClassNotFoundException ignored) {
				continue;
			}
			for (String methodName : methodNames) {
				for (Class<?> paramType : new Class<?>[]{detailKeyMapping.getClass(), detailKeyMapping.getClass().getSuperclass()}) {
					if (paramType == null) continue;
					try {
						Method method = helperClass.getMethod(methodName, paramType);
						Object returned = method.invoke(null, detailKeyMapping);
						// A helper visszaadhat egy csomagolt/módosított példányt — azt kell
						// használnunk a továbbiakban a getKey() hívásokhoz.
						if (returned != null && returned != detailKeyMapping) {
							CommandHelper.LOGGER.info("CH: helper visszatérési érték eltér, frissítjük detailKeyMapping-et");
							detailKeyMapping = returned;
						}
						CommandHelper.LOGGER.info("CH: keybind regisztrálva: {}.{}()", className, methodName);
						keyRegistered = true;
						return;
					} catch (java.lang.reflect.InvocationTargetException ex) {
						CommandHelper.LOGGER.warn("CH: {}.{}() kivételt dobott: {}", className, methodName, ex.getCause().toString());
					} catch (ReflectiveOperationException ignored) {}
				}
			}
			CommandHelper.LOGGER.warn("CH: osztály megtalálva ({}) de egyezö metódus nincs", className);
		}
		CommandHelper.LOGGER.warn("CH: Fabric keybinding helper nem elérhető, tick-alapú array fallback-et használunk");
	}

	private static void registerKeyMapping(Object options) {
		try {
			Class<?> keyMappingClass = Class.forName("net.minecraft.client.KeyMapping");
			Method method = options.getClass().getMethod("registerKey", keyMappingClass);
			method.invoke(options, detailKeyMapping);
			CommandHelper.LOGGER.info("Command Helper: key mapping registered via options.registerKey.");
			return;
		} catch (ReflectiveOperationException ignored) {
			// Fallback below.
		}

		try {
			var field = options.getClass().getDeclaredField("keyMappings");
			field.setAccessible(true);
			Object value = field.get(options);
			if (value != null && value.getClass().isArray()) {
				int length = java.lang.reflect.Array.getLength(value);
				Class<?> componentType = value.getClass().getComponentType();
				Object expanded = java.lang.reflect.Array.newInstance(componentType, length + 1);
				System.arraycopy(value, 0, expanded, 0, length);
				java.lang.reflect.Array.set(expanded, length, detailKeyMapping);
				field.set(options, expanded);
			}
			try {
				Class<?> keyMappingClass = Class.forName("net.minecraft.client.KeyMapping");
				Method resetMethod = keyMappingClass.getMethod("resetMapping");
				resetMethod.invoke(null);
			} catch (ReflectiveOperationException ignored) {
				// Optional refresh.
			}
			CommandHelper.LOGGER.info("Command Helper: key mapping registered via keyMappings array fallback.");
		} catch (ReflectiveOperationException ex) {
			CommandHelper.LOGGER.warn("Command Helper: failed to register key mapping in Controls.", ex);
		}
	}
}
