(function () {
   'use strict';
}());

angular.module('slicebox.anonymization', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/anonymization', {
    templateUrl: '/assets/partials/anonymization.html',
    controller: 'AnonymizationCtrl'
  });
})

.controller('AnonymizationCtrl', function($scope, $http, openMessageModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction, sbxToast) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/anonymization/keys/', 'anonymization key(s)')
            },
            {
                name: 'Export',
                action: $scope.exportToCsv
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/anonymization/keys/')
            }            
        ];

    if (!$scope.uiState.anonymizationTableState) {
        $scope.uiState.anonymizationTableState = {};
    }

    $scope.callbacks = {};

    // Scope functions
    $scope.loadAnonymizationKeyPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/anonymization/keys?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            loadUrl = loadUrl + '&orderby=' + orderByProperty;
            
            if (orderByDirection === 'ASCENDING') {
                loadUrl = loadUrl + '&orderascending=true';
            } else {
                loadUrl = loadUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadUrl = loadUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPromise = $http.get(loadUrl);

        loadPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load anonymization keys: ' + error);
        });

        return loadPromise;
    };

    $scope.exportToCsv = function(keys) {
        var csv = 
            "Id;Created;Patient Name;Patient ID;Anonymous Patient Name;Anonymous Patient ID;Patient Birth Date;" +
            "Study Instance UID;Anonymous Study Instance UID;Study Description;Study ID;Accession Number;" + 
            "Series Instance UID;Anonymous Series Instance UID;Series Description;Protocol Name;Frame Of Reference UID;Anonymous Frame Of Reference UID\n" +
            keys.map(function (key) {
                return key.id + ";" + key.created + ";" + key.patientName + ";" + key.patientID + ";" + key.anonPatientName + ";" + key.anonPatientID + ";" + key.patientBirthDate + ";" +
                    key.studyInstanceUID + ";" + key.anonStudyInstanceUID + ";" + key.studyDescription + ";" + key.studyID + ";" + key.accessionNumber + ";" +
                    key.seriesInstanceUID + ";" + key.anonSeriesInstanceUID + ";" + key.seriesDescription + ";" + key.protocolName + ";" + key.frameOfReferenceUID + ";" + key.anonFrameOfReferenceUID;
            }).join("\n");
        var anchor = "<a class='md-button md-primary' href='data:text/csv;charset=UTF-8," + encodeURIComponent(csv) + "' download='slicebox-anonymization-keys.csv'>Download CSV</a>";
        var textBoxHeader = '<h4>...or copy these values to the clipboard:</h4>';
        var textBox = "<md-content style='height: 200px;padding: 8px;'><pre>" + csv + "</pre></md-content>";
        var body = anchor + textBoxHeader + textBox;
        openMessageModal("Download or copy CSV", body);
    };

    function capitalizeFirst(string) {
        return string.charAt(0).toUpperCase() + string.substring(1);        
    }

});
