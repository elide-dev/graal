"""LLDB assertions for the optimized Native Image declaration/value control."""
import lldb


def check(debugger, command, result, internal_dict):
    try:
        target = debugger.GetSelectedTarget()
        thread = target.GetProcess().GetSelectedThread()
        names = [frame.GetFunctionName() or "" for frame in thread]
        assert any("inner" in name for name in names), names
        assert any("outer" in name for name in names), names
        frame = next(frame for frame in thread if "inner" in (frame.GetFunctionName() or ""))
        assert frame.GetLineEntry().GetFileSpec().GetFilename() == "DebugQualityProbe.java"
        for name, expected in [("value", 18)]:
            value = frame.FindVariable(name)
            error = lldb.SBError()
            actual = value.GetValueAsSigned(error)
            assert value.IsValid() and error.Success() and actual == expected, (name, str(value), str(error))
        declared = target.FindFirstType("debugquality.DebugQualityProbe$DataOnly")
        assert declared.IsValid(), "Code-less DataOnly type is missing"
        fields = [declared.GetFieldAtIndex(index).GetName() for index in range(declared.GetNumberOfFields())]
        assert "value" in fields and "other" in fields, fields
        lookup = lldb.SBCommandReturnObject()
        debugger.GetCommandInterpreter().HandleCommand('image lookup -t "debugquality.DebugQualityProbe$DataOnly"', lookup)
        assert lookup.Succeeded() and "decl = DebugQualityProbe.java" in lookup.GetOutput(), lookup.GetOutput()
        result.PutCString("Optimized source, inline chain, live values and code-less declaration verified")
    except Exception as error:
        result.SetError(str(error))


def __lldb_init_module(debugger, internal_dict):
    debugger.HandleCommand("command script add -f debug_quality_lldb.check debug-quality-check")
