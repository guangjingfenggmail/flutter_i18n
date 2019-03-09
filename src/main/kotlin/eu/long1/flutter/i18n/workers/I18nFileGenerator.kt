package eu.long1.flutter.i18n.workers

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import eu.long1.flutter.i18n.arb.ArbFileType
import eu.long1.flutter.i18n.files.FileHelpers
import java.util.regex.Pattern


class I18nFileGenerator(private val project: Project) {

    private val psiManager = PsiManager.getInstance(project)
    private val documentManager = PsiDocumentManager.getInstance(project)
    private val valuesFolder = FileHelpers.getValuesFolder(project)

    fun generate() {
        val files = stringFiles()
        if (files.isEmpty()) files.add(createFileForLang("en"))

        val data = HashMap<String, HashMap<String, String>>()

        files.forEach {
            val lang = it.nameWithoutExtension.substringAfter("_")
            val json = psiManager.findFile(it) as JsonFile
            data[lang] = getStringFromFile(json) ?: HashMap()
        }

        val builder = StringBuilder()
        builder.append(i18nFileImports)
        builder.append("// ignore_for_file: non_constant_identifier_names\n// ignore_for_file: camel_case_types\n// ignore_for_file: prefer_single_quotes\n\n")
        builder.append("// This file is automatically generated. DO NOT EDIT, all your changes would be lost.\n")
        appendSClass(data["en"]!!, builder)

        val className = "\$en"
        data.keys.forEach {
            if (it == "en") builder.append("class $className extends S {\n  const $className();\n}\n\n")
            else appendLangClass(it, data, builder)
        }

        appendDelegateClass(data, builder)

        val i18nFile = builder.toString()

        val file = FileHelpers.getI18nFile(project)
        val dartFile = psiManager.findFile(file)!!
        val document = documentManager.getDocument(dartFile)!!

        if (document.text != i18nFile) {
            ApplicationManager.getApplication().invokeLater {
                runWriteAction {
                    document.setText(i18nFile)
                    documentManager.commitDocument(document)
                }
            }
        }
    }

    private fun stringFiles(): ArrayList<VirtualFile> = valuesFolder.children.filter {
        it.extension == ArbFileType.defaultExtension && it.name.startsWith("strings_", ignoreCase = true)
    } as ArrayList

    private fun appendSClass(en: HashMap<String, String>, builder: StringBuilder) {
        val ids = ArrayList<String>(en.keys)

        val pluralsMaps = HashMap<String, ArrayList<String>>()
        val plurals = findPluralsIds(ids, pluralsMaps)
        ids -= plurals

        val parametrized = ids.filter { en[it]!!.contains("$") }
        ids -= parametrized

        builder.append(sClassHeader)

        ids.sort()
        ids.forEach { appendStringMethod(it, en[it]!!, builder, false) }

        parametrized.sorted().forEach { appendParametrizedMethod(it, en[it]!!, builder, false) }
        pluralsMaps.keys.sorted().forEach { appendPluralMethod(it, pluralsMaps[it]!!, en, builder, false) }

        builder.append("}\n\n")
    }

    private fun appendLangClass(lang: String, map: HashMap<String, HashMap<String, String>>, builder: StringBuilder) {
        val langMap = map[lang]!!
        val enIds = ArrayList<String>(map["en"]!!.keys)
        val ids = ArrayList(langMap.keys).filter { enIds.contains(it) } as ArrayList

        val pluralsMaps = HashMap<String, ArrayList<String>>()
        val plurals = findPluralsIds(ids, pluralsMaps)
        ids -= plurals

        val parametrized = ids.filter { langMap[it]!!.contains("$") }
        ids -= parametrized


        var className = "\$$lang"

        builder.append(
            "class $className extends S {\n  const $className();\n\n  " +
                    "@override\n  TextDirection get textDirection => TextDirection.${if (rtl.contains(lang.split("_")[0])) "rtl" else "ltr"};\n\n"
        )

        ids.forEach { appendStringMethod(it, langMap[it]!!, builder) }
        parametrized.forEach { appendParametrizedMethod(it, langMap[it]!!, builder) }
        pluralsMaps.keys.forEach { appendPluralMethod(it, pluralsMaps[it]!!, langMap, builder) }

        builder.append("}\n\n")

        //for hebrew iw=he
        if (lang.startsWith("iw")) {
            className = "\$he_IL"
            builder.append(
                "class $className extends \$$lang {\n const $className();\n\n   " +
                        "@override\n  TextDirection get textDirection => TextDirection.rtl;\n\n}"
            )
        }
    }

    private fun appendDelegateClass(map: HashMap<String, HashMap<String, String>>, builder: StringBuilder) {
        builder.append(delegateClassHeader)
        map.keys.forEach {
            val langParts = it.split("_")
            val lang = langParts[0]
            val country = if (langParts.size == 2) langParts[1] else ""


            //for hebrew iw=he
            if (it.startsWith("iw")) {
                builder.append("      Locale(\"he\", \"IL\"),\n")
            } else builder.append("      Locale(\"$lang\", \"$country\"),\n")
        }

        builder.append(delegateClassResolution)
        map.keys.forEach {


            //for hebrew iw=he
            if (it.startsWith("iw")) {
                builder.append("        case \"iw_IL\":\n      case \"he_IL\":\n          S.current = const \$he_IL();\nreturn SynchronousFuture<S>(S.current);\n")
            } else {
                builder.append("        case \"$it\":\n          S.current = const \$$it();\n          return SynchronousFuture<S>(S.current);\n")
            }
        }

        builder.append(delegateClassEnd)
    }


    internal fun appendStringMethod(id: String, value: String, builder: StringBuilder, isOverride: Boolean = true) {
        if (isOverride) {
            builder.append("  @override\n")
        }
        builder.append("  String get $id => \"$value\";\n")
    }

    internal fun appendParametrizedMethod(
        id: String,
        value: String,
        builder: StringBuilder,
        isOverride: Boolean = true
    ) {
        PARAMETER_MATCHER.reset(value)

        if (isOverride) {
            builder.append("  @override\n")
        }

        var hasItems = false
        while (PARAMETER_MATCHER.find()) {
            if (!hasItems) {
                builder.append("  String $id(")
                hasItems = true
            }

            val parameter = normalizeParameter(PARAMETER_MATCHER.group())
            builder.append("String $parameter, ")
        }

        if (hasItems) {
            builder.setLength(builder.length - 2)
            builder.append(")")
        } else {
            builder.append("  String get $id")
        }

        builder.append(" => \"$value\";\n")
    }

    internal fun appendPluralMethod(
        id: String, countsList: ArrayList<String>, valuesMap: HashMap<String, String>,
        builder: StringBuilder, isOverride: Boolean = true
    ) {

        val zero = countsList.contains("zero")
        val one = countsList.contains("one")
        val two = countsList.contains("two")
        val few = countsList.contains("few")
        val many = countsList.contains("many")


        val otherValue = valuesMap["${id}Other"] ?: valuesMap["${id}other"] ?: return
        val parameterName: String =
            if (PARAMETER_MATCHER.reset(otherValue).find()) {
                normalizeParameter(PARAMETER_MATCHER.group())
            } else {
                "param"
            }


        if (isOverride) {
            builder.append("  @override\n")
        }

        val newId: String = if (id.endsWith("_")) id.dropLast(1) else id

        builder.append(
            "  String $newId(dynamic $parameterName) {\n    switch ($parameterName.toString()) {\n"
        )

        if (zero) {
            val value = valuesMap["${id}Zero"] ?: valuesMap["${id}zero"]
            builder.append("      case \"0\":\n        return \"$value\";\n")
        }
        if (one) {
            val value = valuesMap["${id}One"] ?: valuesMap["${id}one"]
            builder.append("      case \"1\":\n        return \"$value\";\n")
        }
        if (two) {
            val value = valuesMap["${id}Two"] ?: valuesMap["${id}two"]
            builder.append("      case \"2\":\n        return \"$value\";\n")
        }
        if (few) {
            val value = valuesMap["${id}Few"] ?: valuesMap["${id}few"]
            builder.append("      case \"few\":\n        return \"$value\";\n")
        }
        if (many) {
            val value = valuesMap["${id}Many"] ?: valuesMap["${id}many"]
            builder.append("      case \"many\":\n        return \"$value\";\n")
        }

        builder.append("      default:\n        return \"$otherValue\";\n    }\n  }\n")
    }

    fun getCountFromValue(text: String): String? = when (text) {
        "0" -> "Zero"
        "1" -> "One"
        "2" -> "Two"
        "few" -> "Few"
        "many" -> "Many"
        else -> throw IllegalArgumentException("This value $text is not valid.")
    }

    /**
     * Trim possible surrounding curly parentheses used in Flutter, in addition to the dollar sign used by parameters.
     */
    private fun normalizeParameter(parameter: String): String = parameter.trim('$', '{', '}')

    /**
     * Create a file in the values folder for the given language.
     */
    private fun createFileForLang(lang: String): VirtualFile {
        val virtualFile = valuesFolder.findOrCreateChildData(this, "strings_$lang.arb")
        val psiFile = psiManager.findFile(virtualFile)!! as JsonFile
        val doc = documentManager.getDocument(psiFile)!!
        doc.setText("{}")
        CodeStyleManager.getInstance(psiManager).reformat(psiFile)
        return virtualFile
    }

    /**
     * Searches for plurals in the ids of the strings and return a list will al of them.
     *
     * @param ids contains the list that needs to be searched for plurals
     * @param pluralsMaps we append to this map the id of the plural and a list of all the qualities("One", "Two", ...)
     * that were declared.
     *
     * @return A list with the ids that are considered plurals and that will be treated separately.
     *
     * NOTE: It is not considered a plural when the Other quantity is not declared. In this case the other qualities
     * will be treated as independent ids.
     */
    private fun findPluralsIds(ids: ArrayList<String>, pluralsMaps: HashMap<String, ArrayList<String>>): List<String> {
        val map = HashMap<String, ArrayList<String>>()
        val pluralIds = ids.filter { value ->
            val isPlural = pluralEnding.any { value.endsWith(it, ignoreCase = true) }
            if (isPlural) {
                val quantity = pluralEnding.first { value.endsWith(it, ignoreCase = true) }
                val id = value.substring(0, value.length - quantity.length)
                val list = map[id] ?: ArrayList()
                list.add(quantity.toLowerCase())
                map[id] = list
            }

            isPlural
        } as ArrayList

        HashMap(map).forEach { id, counts ->
            if (counts.none { it.contains("other", true) }) {
                counts.forEach { count -> pluralIds.remove("$id$count") }
                map.remove(id)
            }
        }

        pluralsMaps.putAll(map)
        return pluralIds
    }


    private fun getStringFromFile(file: PsiFile): HashMap<String, String>? {
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return null
        val langMap = HashMap<String, String>()

        file.children.first().children.filterIsInstance<JsonProperty>()
            .filter { !it.name.startsWith("@") }
            .forEach { langMap[it.name] = it.value?.text!!.drop(1).dropLast(1) }
        return langMap
    }

    companion object {
        private val PARAMETER_MATCHER =
            Pattern.compile(
                "(?<!\\\\)\\\$\\{?(.+?]*\\b)}?"
            )
                .matcher("")

        // @formatter:off
        private const val i18nFileImports =
            """import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

"""

        private const val sClassHeader =
            """class S implements WidgetsLocalizations {
  const S();

  static S current;

  static const GeneratedLocalizationsDelegate delegate =
    GeneratedLocalizationsDelegate();

  static S of(BuildContext context) => Localizations.of<S>(context, S);

  @override
  TextDirection get textDirection => TextDirection.ltr;

"""

        private const val delegateClassHeader =
            """class GeneratedLocalizationsDelegate extends LocalizationsDelegate<S> {
  const GeneratedLocalizationsDelegate();

  List<Locale> get supportedLocales {
    return const <Locale>[
"""

        private const val delegateClassResolution =
            """    ];
  }

  LocaleListResolutionCallback listResolution({Locale fallback, bool withCountry = true}) {
    return (List<Locale> locales, Iterable<Locale> supported) {
      if (locales == null || locales.isEmpty) {
        return fallback ?? supported.first;
      } else {
        return _resolve(locales.first, fallback, supported, withCountry);
      }
    };
  }

  LocaleResolutionCallback resolution({Locale fallback, bool withCountry = true}) {
    return (Locale locale, Iterable<Locale> supported) {
      return _resolve(locale, fallback, supported, withCountry);
    };
  }

  @override
  Future<S> load(Locale locale) {
    final String lang = getLang(locale);
    if (lang != null) {
      switch (lang) {
"""

        private const val delegateClassEnd =
            """        default:
          // NO-OP.
      }
    }
    S.current = const S();
    return SynchronousFuture<S>(S.current);
  }

  @override
  bool isSupported(Locale locale) => _isSupported(locale, true);

  @override
  bool shouldReload(GeneratedLocalizationsDelegate old) => false;

  ///
  /// Internal method to resolve a locale from a list of locales.
  ///
  Locale _resolve(Locale locale, Locale fallback, Iterable<Locale> supported, bool withCountry) {
    if (locale == null || !_isSupported(locale, withCountry)) {
      return fallback ?? supported.first;
    }

    final Locale languageLocale = Locale(locale.languageCode, "");
    if (supported.contains(locale)) {
      return locale;
    } else if (supported.contains(languageLocale)) {
      return languageLocale;
    } else {
      final Locale fallbackLocale = fallback ?? supported.first;
      return fallbackLocale;
    }
  }

  ///
  /// Returns true if the specified locale is supported, false otherwise.
  ///
  bool _isSupported(Locale locale, bool withCountry) {
    if (locale != null) {
      for (Locale supportedLocale in supportedLocales) {
        // Language must always match both locales.
        if (supportedLocale.languageCode != locale.languageCode) {
          continue;
        }

        // If country code matches, return this locale.
        if (supportedLocale.countryCode == locale.countryCode) {
          return true;
        }

        // If no country requirement is requested, check if this locale has no country.
        if (true != withCountry && (supportedLocale.countryCode == null || supportedLocale.countryCode.isEmpty)) {
          return true;
        }
      }
    }
    return false;
  }
}

String getLang(Locale l) => l == null
  ? null
  : l.countryCode != null && l.countryCode.isEmpty
    ? l.languageCode
    : l.toString();
"""
// @formatter:on

        private val rtl: Set<String> = setOf("ar", "dv", "fa", "ha", "he", "iw", "ji", "ps", "ur", "yi")
        private val pluralEnding: ArrayList<String> =
            arrayListOf("zero", "one", "two", "few", "many", "other")
    }
}
