with open('plugins/QuickMessageActions/src/main/java/dev/autoaliu/generated/quickmessageactions/QuickMessageActions.java', 'r') as f:
    lines = f.readlines()

out = []
found_active = False
for line in lines:
    if 'private long activeMessageId' in line:
        if found_active:
            continue
        found_active = True
    if 'params.setMarginTop(' in line:
        line = line.replace('params.setMarginTop(DimenUtils.dpToPx(4));', 'params.topMargin = DimenUtils.dpToPx(4);')
    if 'params.setMarginBottom(' in line:
        line = line.replace('params.setMarginBottom(DimenUtils.dpToPx(4));', 'params.bottomMargin = DimenUtils.dpToPx(4);')
    out.append(line)

with open('plugins/QuickMessageActions/src/main/java/dev/autoaliu/generated/quickmessageactions/QuickMessageActions.java', 'w') as f:
    f.writelines(out)

