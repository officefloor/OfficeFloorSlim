package net.officefloor.intellij.flow.slim;

import static org.junit.Assert.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.Test;

import net.officefloor.intellij.flow.layout.LayeredLayout;
import net.officefloor.intellij.flow.layout.LayoutResult;
import net.officefloor.intellij.flow.slim.SlimEnricher.NodeSlim;
import net.officefloor.intellij.flow.ui.FlowDiagramPanel;
import net.officefloor.officefloorslim.core.MethodSplit;
import net.officefloor.officefloorslim.graph.FlowGraph;
import net.officefloor.officefloorslim.graph.FlowLink;
import net.officefloor.officefloorslim.graph.FlowNode;
import net.officefloor.officefloorslim.graph.LinkKind;
import net.officefloor.officefloorslim.graph.NodeKind;

/**
 * The diagram painted off screen with a long function's split card open: it paints without
 * failing, and the image is written to build/ for a look.
 */
public class SplitPreviewPaintTest {

	@Test
	public void aLongFunctionsCardPaints() throws Exception {
		StringBuilder b = new StringBuilder("class Orders {\n\tint place(int a) {\n");
		for (int i = 0; i < 10; i++) {
			b.append("\t\tif (a == ").append(i).append(") {\n\t\t\treturn ").append(i).append(";\n\t\t}\n");
		}
		b.append("\t\tint s = 0;\n\t\tfor (int i = 0; i < a; i++) {\n");
		for (int i = 0; i < 30; i++) {
			b.append("\t\t\ts += i * ").append(i).append(";\n");
		}
		b.append("\t\t}\n\t\treturn s;\n\t}\n}\n");
		MethodSplit place = MethodSplit.at("Orders.java", b.toString(), 2);

		FlowGraph graph = new FlowGraph();
		graph.addNode(new FlowNode("POST /orders", NodeKind.HTTP_ENDPOINT, "POST /orders", "", -1, null, null));
		graph.addNode(new FlowNode("place", NodeKind.PROCEDURE, "place", "Orders#place", -1, "Orders", "place"));
		graph.addNode(new FlowNode("notify", NodeKind.PROCEDURE, "notify", "Mail#notify", -1, "Mail", "notify"));
		graph.addLink(new FlowLink("POST /orders", "place", LinkKind.ROUTE, "", -1));
		graph.addLink(new FlowLink("place", "notify", LinkKind.NEXT, "", -1));
		LayoutResult layout = new LayeredLayout().layout(graph);

		FlowDiagramPanel panel = new FlowDiagramPanel(graph, layout);
		panel.setSlims(Map.of("place", new NodeSlim(null, place)));
		panel.togglePinned("place");
		int w = (int) layout.getWidth() + 720, h = (int) layout.getHeight() + 220;
		panel.setSize(w, h);
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		panel.paint(g);
		g.dispose();
		// pinned, the long function is drawn split: its two proposed functions in its place
		File out = new File("build/split-preview.png");
		ImageIO.write(image, "png", out);
		assertTrue(out.length() > 0);
	}
}
