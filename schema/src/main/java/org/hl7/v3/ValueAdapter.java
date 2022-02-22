package org.hl7.v3;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.adapters.XmlAdapter;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Element;

public class ValueAdapter extends XmlAdapter<Object, Object> {
    private static final String VALUE_ATTRIBUTE = "value";
    private static final String UNIT_ATTRIBUTE = "unit";
    private static final String TRANSLATION_ELEMENT = "translation";

    @Override
    public Object unmarshal(Object v) {
        if (v instanceof PQ || v instanceof IVLPQ || v instanceof CV) {
            return v;
        }

        Element element = (Element) v;
        if (element.hasAttribute(VALUE_ATTRIBUTE)) {
            PQ pq = new PQ();
            pq.setValue(element.getAttribute(VALUE_ATTRIBUTE));
            setPqUnit(element, pq);
            setPqTranslations(element, pq);

            return pq;
        }

        return element.getTextContent();
    }

    @Override
    public Object marshal(Object v) {
        return v;
    }

    private void setPqUnit(Element element, PQ pq) {
        var pqUnit = element.getAttribute(UNIT_ATTRIBUTE);
        if (!StringUtils.isEmpty(pqUnit)) {
            pq.setUnit(pqUnit);
        }
    }

    private void setPqTranslations(Element element, PQ pq) {
        List<PQR> translations = getTranslations(element);
        if (!translations.isEmpty()) {
            pq.getTranslation().addAll(translations);
        }
    }

    private List<PQR> getTranslations(Element element) {
        List<PQR> translations = new ArrayList<>();
        var translationChildNodes = element.getChildNodes();
        for (int i = 0; i < translationChildNodes.getLength(); i++) {
            var translationChildNode = translationChildNodes.item(i);
            if (translationChildNodes.item(i).getNodeName().equals(TRANSLATION_ELEMENT)) {
                PQR translation = new PQR();
                translation.setValue(translationChildNode.getAttributes().getNamedItem(VALUE_ATTRIBUTE).getNodeValue());
                translation.setOriginalText(translationChildNode.getFirstChild().getFirstChild().getNodeValue());

                translations.add(i, translation);
            }
        }
        return translations;
    }
}