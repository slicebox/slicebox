(function () {
   'use strict';
}());

angular.module('slicebox.import', ['ngRoute', 'ngFileUpload'])

.config(function($routeProvider) {
  $routeProvider.when('/import', {
    templateUrl: '/assets/partials/import.html',
    controller: 'ImportCtrl'
  });
})

.controller('ImportCtrl', function($scope, $http, $interval, Upload, $q, sbxToast, openAddEntityModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {

    $scope.sessionActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/import/sessions/', 'import sessions')
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/import/sessions/')
            }
        ];

    if (!$scope.uiState.sessionTableState) {
        $scope.uiState.sessionTableState = {};
        $scope.uiState.selectedSession = null;
        $scope.uiState.currentFileSet = {
            processing: false,
            index: 0,
            total: 0,
            progress: 0
        };
    }

    $scope.callbacks = {};

    var timer = $interval(function() {
        if ($scope.uiState.currentFileSet.processing) {
            $scope.callbacks.importSessionsTable.reloadPage();
        }
    }, 10000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });

    $scope.loadImportSessions = function(startIndex, count) {
        var sessionsPromise = $http.get('/api/import/sessions?startindex=' + startIndex + '&count=' + count);
        return sessionsPromise;
    };

    $scope.addImportSessionButtonClicked = function() {
        openAddEntityModal(
            'addImportSessionModalContent.html',
            'AddImportSessionModalCtrl',
            '/api/import/sessions/',
            'Import session',
            $scope.callbacks.importSessionsTable)
        .then(function (importSession) {
            $scope.callbacks.importSessionsTable.selectObject(importSession);
        });
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    $scope.import = function(filesAndDirectories) {
        var files = filesAndDirectories.filter(function (fileOrDirectory) {
            return fileOrDirectory.type !== 'directory';
        });

        var nConcurrent = Math.min(10, files.length);
        var nUploading = 0;

        $scope.uiState.currentFileSet.processing = true;
        $scope.uiState.currentFileSet.index = 0;
        $scope.uiState.currentFileSet.total = files.length;
        $scope.uiState.currentFileSet.progress = 0;

        var prepareNext = function() {
            if (files.length) {
                next(files.shift());
            } else if (nUploading <= 0) {
                $scope.uiState.currentFileSet.processing = false;
                $scope.callbacks.importSessionsTable.reloadPage();
            }
        };

        var next = function(file) {
            $scope.uiState.currentFileSet.index++;
            $scope.uiState.currentFileSet.progress = Math.round(100 * $scope.uiState.currentFileSet.index / $scope.uiState.currentFileSet.total);

            nUploading += 1;

            Upload.upload({
                url: '/api/import/sessions/' + $scope.uiState.selectedSession.id + '/images',
                data: {file: file}
            }).success(function () {
                nUploading -= 1;
                prepareNext();
            }).error(function (message, status) {
                if (status >= 300 && status !== 400) {
                    sbxToast.showErrorMessage(message);
                }
                nUploading -= 1;
                prepareNext();
            });
        };

        // start nConcurrent uploads
        for (var i = 0; i < nConcurrent; i++) {
            next(files.shift());
        }
    };

})

.controller('AddImportSessionModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        return $mdDialog.hide({
            id: -1,
            name: $scope.name,
            userId: -1,
            user: "",
            filesImported: 0,
            filesAdded: 0,
            filesRejected: 0,
            created: 0,
            lastUpdated: 0
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});