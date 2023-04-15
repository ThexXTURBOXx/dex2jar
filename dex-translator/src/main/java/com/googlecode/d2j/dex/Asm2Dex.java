package com.googlecode.d2j.dex;

import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.cf.iface.ParseException;
import com.android.dx.cf.iface.ParseObserver;
import com.android.dx.command.dexer.DxContext;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.file.ClassDefItem;
import com.android.dx.dex.file.DexFile;
import com.googlecode.d2j.DexConstants;
import com.googlecode.d2j.node.DexClassNode;
import com.googlecode.d2j.node.DexFileNode;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Asm2Dex {
	private final Map<String, byte[]> classes = new HashMap<>();
	private Function<ClassNode, byte[]> asmNodeToBytes = n -> {
		// We will assume class has already computed maxs/frames.
		// If not the user can provide an override mapping function.
		ClassWriter cw = new ClassWriter(0);
		n.accept(cw);
		return cw.toByteArray();
	};
	private Function<DexClassNode, byte[]> dexNodeToBytes = n -> {
		// Ideally we can get rid of this, since converting to Java to then
		// convert back to dex is silly.
		Dex2Asm dex2Asm = new Dex2Asm();
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		dex2Asm.convertClass(n, classInternalName -> cw);
		return cw.toByteArray();
	};
	private Supplier<DexFileNode> dexFileProvider = DexFileNode::new;
	private boolean dxStrictNameCheck = false;
	private boolean dxIncludeLocalInfo = false;
	private boolean dxOptimize = false;
	private boolean dxWriteVerbose = false;
	private Writer dxWriterOutput = null;
	private ParseObserver dxParseObserver = null;

	public Asm2Dex setDxStrictNameCheck(boolean dxStrictNameCheck) {
		this.dxStrictNameCheck = dxStrictNameCheck;
		return this;
	}

	public Asm2Dex setDxIncludeLocalInfo(boolean dxIncludeLocalInfo) {
		this.dxIncludeLocalInfo = dxIncludeLocalInfo;
		return this;
	}

	public Asm2Dex setDxOptimize(boolean dxOptimize) {
		this.dxOptimize = dxOptimize;
		return this;
	}

	public Asm2Dex setDxWriteVerbose(boolean dxWriteVerbose) {
		this.dxWriteVerbose = dxWriteVerbose;
		return this;
	}

	public Asm2Dex setDxWriterOutput(Writer dxWriterOutput) {
		this.dxWriterOutput = dxWriterOutput;
		return this;
	}

	public Asm2Dex setDxParseObserver(ParseObserver dxParseObserver) {
		this.dxParseObserver = dxParseObserver;
		return this;
	}

	public Asm2Dex setDexFileProvider(Supplier<DexFileNode> dexFileProvider) {
		this.dexFileProvider = dexFileProvider;
		return this;
	}

	public Asm2Dex setAsmNodeToBytes(Function<ClassNode, byte[]> asmNodeToBytes) {
		this.asmNodeToBytes = asmNodeToBytes;
		return this;
	}

	public Asm2Dex setDexNodeToBytes(Function<DexClassNode, byte[]> dexNodeToBytes) {
		this.dexNodeToBytes = dexNodeToBytes;
		return this;
	}

	public Asm2Dex addJvmClass(String internalName, byte[] clz) {
		classes.put(internalName, clz);
		return this;
	}

	public Asm2Dex addJvmClasses(Map<String, byte[]> clzMap) {
		classes.putAll(clzMap);
		return this;
	}

	public Asm2Dex addJvmClassNode(String internalName, ClassNode clz) {
		classes.put(internalName, asmNodeToBytes.apply(clz));
		return this;
	}

	public Asm2Dex addJvmClassNodes(Map<String, ClassNode> clzMap) {
		Map<String, byte[]> mapped = clzMap.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> asmNodeToBytes.apply(e.getValue())));
		addJvmClasses(mapped);
		return this;
	}

	public byte[] buildDexFile() throws ParseException, IOException {
		DexFileNode dexFileNode = dexFileProvider.get();

		// Use DX to create a dex file
		CfOptions cfOptions = new CfOptions();
		cfOptions.strictNameCheck = dxStrictNameCheck;
		cfOptions.localInfo = dxIncludeLocalInfo;
		cfOptions.optimize = dxOptimize;
		DexOptions dexOptions = new DexOptions();
		if (dexFileNode != null && dexFileNode.dexVersion >= DexConstants.DEX_037) {
			dexOptions.minSdkVersion = 26;
		}
		DexFile dxFileOutput = new DexFile(dexOptions);


		DxContext context = new DxContext();
		BiConsumer<String, byte[]> javaDexInputConsumer = (className, classBytes) -> {
			DirectClassFile dcf = new DirectClassFile(classBytes, className + ".class", true);
			dcf.setAttributeFactory(new StdAttributeFactory());
			dcf.setObserver(dxParseObserver);

			// Translate to dalvik
			ClassDefItem item = CfTranslator.translate(context, dcf, classBytes, cfOptions, dexOptions, dxFileOutput);

			// TODO: Is this process needed, does translate do it for us?
			dxFileOutput.add(item);
		};

		// Add class file entries
		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			String className = entry.getKey();
			byte[] classBytes = entry.getValue();
			javaDexInputConsumer.accept(className, classBytes);
		}

		// Add our existing DexClassNode's entries
		if (dexFileNode != null && !dexFileNode.clzs.isEmpty()) {
			for (DexClassNode clz : dexFileNode.clzs) {
				// TODO: Ideally we do not have to convert DEX to Java before back into DEX
				//       We need to map our DexClassNode into an input that can be consumed here.
				//         Replace with one-liner: Dex2DxDef.appendToDexFile(dxFileOutput, clz);
				String className = clz.className.substring(1, clz.className.length() - 1);
				byte[] classBytes = dexNodeToBytes.apply(clz);
				javaDexInputConsumer.accept(className, classBytes);
			}
		}

		byte[] dxBytes = dxFileOutput.toDex(dxWriterOutput, dxWriteVerbose);
		return dxBytes;
	}
}
