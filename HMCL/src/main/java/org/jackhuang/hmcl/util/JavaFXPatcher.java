/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Utility for Adding JavaFX to module path.
 *
 * @author ZekerZhayard
 */
public final class JavaFXPatcher {
    private JavaFXPatcher() {
    }

    /**
     * Add JavaFX to module path at runtime.
     *
     * @param modules  All module names
     * @param jarPaths All jar paths
     * @throws ReflectiveOperationException When the call to add these jars to the system module path failed.
     */
    public static void patch(Set<String> modules, Path[] jarPaths, String[] addOpens) throws ReflectiveOperationException {
        // Find all modules
        ModuleFinder finder = ModuleFinder.of(jarPaths);

        // Load all modules as unnamed module
        for (ModuleReference mref : finder.findAll()) {
            ((jdk.internal.loader.BuiltinClassLoader) ClassLoader.getSystemClassLoader()).loadModule(mref);
        }

        // Define all modules
        Configuration config = Configuration.resolveAndBind(finder, List.of(ModuleLayer.boot().configuration()), finder, modules);
        ModuleLayer layer = ModuleLayer.defineModules(config, List.of(ModuleLayer.boot()), name -> ClassLoader.getSystemClassLoader()).layer();

        // Add-Exports and Add-Opens
        try {
            // Some hacks
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Module.class, MethodHandles.lookup());
            MethodHandle handle = lookup.findVirtual(Module.class, "implAddOpensToAllUnnamed", MethodType.methodType(void.class, String.class));
            for (String target : addOpens) {
                String[] name = target.split("/", 2); // <module>/<package>
                layer.findModule(name[0]).ifPresent(m -> {
                    try {
                        handle.invokeWithArguments(m, name[1]);
                    } catch (Throwable throwable) {
                        throw new RuntimeException(throwable);
                    }
                });
            }
        } catch (Throwable t) {
            throw new ReflectiveOperationException(t);
        }
    }

    /// FSL: adds extra JavaFX modules (such as `javafx.web`) on top of the already loaded JavaFX.
    ///
    /// The new modules are resolved in a child layer of the layer containing `javafx.graphics`, and are
    /// mapped to the system class loader like [#patch], so launcher code can use them directly.
    /// Modules already present in a parent layer (for example `jdk.jsobject` on older JDKs) are reused.
    ///
    /// @param modules  root module names to resolve
    /// @param jarPaths jars that may provide the root modules and their missing dependencies
    /// @throws IllegalStateException       if JavaFX is not loaded as named modules
    /// @throws ReflectiveOperationException if registering the modules with the system class loader fails
    public static void patchExtraModules(Set<String> modules, Path[] jarPaths) throws ReflectiveOperationException {
        ModuleLayer parent = javafx.scene.Node.class.getModule().getLayer();
        if (parent == null) {
            throw new IllegalStateException("JavaFX is not loaded as named modules");
        }

        // Prefer modules from the parent layers; only resolve what is actually missing from the jars
        Configuration config = parent.configuration().resolveAndBind(ModuleFinder.of(), ModuleFinder.of(jarPaths), modules);

        try {
            for (var resolved : config.modules()) {
                ((jdk.internal.loader.BuiltinClassLoader) ClassLoader.getSystemClassLoader()).loadModule(resolved.reference());
            }
        } catch (Throwable t) {
            throw new ReflectiveOperationException(t);
        }

        ModuleLayer layer = ModuleLayer.defineModules(config, List.of(parent), name -> ClassLoader.getSystemClassLoader()).layer();

        // Qualified exports/opens declared by the parent layer (e.g. javafx.graphics exporting internals
        // to javafx.web) only apply to modules resolved together with it, so wire them up by hand.
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Module.class, MethodHandles.lookup());
            MethodHandle addExports = lookup.findVirtual(Module.class, "implAddExports", MethodType.methodType(void.class, String.class, Module.class));
            MethodHandle addOpens = lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(void.class, String.class, Module.class));
            for (Module module : parent.modules()) {
                for (ModuleDescriptor.Exports exports : module.getDescriptor().exports()) {
                    for (String target : exports.targets()) {
                        Optional<Module> targetModule = layer.findModule(target);
                        if (targetModule.isPresent()) {
                            addExports.invoke(module, exports.source(), targetModule.get());
                        }
                    }
                }
                for (ModuleDescriptor.Opens opens : module.getDescriptor().opens()) {
                    for (String target : opens.targets()) {
                        Optional<Module> targetModule = layer.findModule(target);
                        if (targetModule.isPresent()) {
                            addOpens.invoke(module, opens.source(), targetModule.get());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            throw new ReflectiveOperationException(t);
        }
    }
}
