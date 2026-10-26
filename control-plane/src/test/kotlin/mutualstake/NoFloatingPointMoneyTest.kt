package mutualstake

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test

/** "All money is integer minor units. Never a float, never a double." Checked on the compiled code. */
class NoFloatingPointMoneyTest {

    private val banned = setOf("double", "float", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal")

    private val noFloatingPoint = object : ArchCondition<JavaClass>("declare no float, double or BigDecimal") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            for (field in item.fields) {
                if (field.rawType.name in banned) events.add(SimpleConditionEvent.violated(field, "${field.fullName} is ${field.rawType.name}"))
            }
            for (code in item.codeUnits) {
                if (code.rawReturnType.name in banned) events.add(SimpleConditionEvent.violated(code, "${code.fullName} returns ${code.rawReturnType.name}"))
                for (p in code.rawParameterTypes) {
                    if (p.name in banned) events.add(SimpleConditionEvent.violated(code, "${code.fullName} takes ${p.name}"))
                }
            }
        }
    }

    @Test
    fun `no production class declares a floating-point type`() {
        val production = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("mutualstake")
        classes().that().resideInAPackage("mutualstake..").should(noFloatingPoint).check(production)
    }
}
