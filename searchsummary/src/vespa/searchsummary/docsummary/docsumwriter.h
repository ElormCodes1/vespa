// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "juniperproperties.h"
#include "resultclass.h"
#include "resultconfig.h"
#include "docsumstore.h"
#include "keywordextractor.h"
#include "docsum_field_writer.h"
#include <vespa/searchlib/util/rawbuf.h>
#include <vespa/fastlib/text/unicodeutil.h>
#include <vespa/fastlib/text/wordfolder.h>

namespace search { class IAttributeManager; }

namespace vespalib { class Slime; }

namespace search::docsummary {

static constexpr uint32_t SLIME_MAGIC_ID = 0x55555555;

class IDocsumWriter
{
public:
    struct ResolveClassInfo {
        bool mustSkip;
        bool allGenerated;
        uint32_t outputClassId;
        const ResultClass *outputClass;
        const ResultClass::DynamicInfo *outputClassInfo;
        ResolveClassInfo()
            : mustSkip(false), allGenerated(false),
              outputClassId(ResultConfig::NoClassID()),
              outputClass(nullptr), outputClassInfo(nullptr)
        { }
    };

    virtual ~IDocsumWriter() {}
    virtual void InitState(search::IAttributeManager & attrMan, GetDocsumsState *state) = 0;
    virtual uint32_t WriteDocsum(uint32_t docid, GetDocsumsState *state,
                                 IDocsumStore *docinfos, search::RawBuf *target) = 0;
    virtual void insertDocsum(const ResolveClassInfo & rci, uint32_t docid, GetDocsumsState *state,
                              IDocsumStore *docinfos, vespalib::slime::Inserter & target) = 0;
    virtual ResolveClassInfo resolveClassInfo(vespalib::stringref outputClassName) const = 0;

    static uint32_t slime2RawBuf(const vespalib::Slime & slime, RawBuf & buf);
};

//--------------------------------------------------------------------------

class DynamicDocsumWriter : public IDocsumWriter
{
private:
    ResultConfig        *_resultConfig;
    KeywordExtractor    *_keywordExtractor;
    uint32_t             _numClasses;
    uint32_t             _numEnumValues;
    uint32_t             _numFieldWriterStates;
    ResultClass::DynamicInfo *_classInfoTable;
    DocsumFieldWriter**  _overrideTable;

    ResolveClassInfo resolveOutputClass(vespalib::stringref outputClassName) const;

public:
    DynamicDocsumWriter(ResultConfig *config, KeywordExtractor *extractor);
    DynamicDocsumWriter(const DynamicDocsumWriter &) = delete;
    DynamicDocsumWriter& operator=(const DynamicDocsumWriter &) = delete;
    ~DynamicDocsumWriter() override;

    ResultConfig *GetResultConfig() { return _resultConfig; }

    bool Override(const char *fieldName, DocsumFieldWriter *writer);
    void InitState(search::IAttributeManager & attrMan, GetDocsumsState *state) override;
    uint32_t WriteDocsum(uint32_t docid, GetDocsumsState *state,
                         IDocsumStore *docinfos, search::RawBuf *target) override;

    void insertDocsum(const ResolveClassInfo & outputClassInfo, uint32_t docid, GetDocsumsState *state,
                      IDocsumStore *docinfos, vespalib::slime::Inserter & target) override;

    ResolveClassInfo resolveClassInfo(vespalib::stringref outputClassName) const override;
};

}
